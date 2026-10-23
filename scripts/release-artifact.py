#!/usr/bin/env python3
"""Event-driven release handoff, immutable archive checks and release ownership."""

import argparse
import hashlib
import io
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ".github/workflows/ci.yml"
PLUGIN_ID = "dev.koltyakov.varro-openjet"
VERSION = r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?"


def release_version(tag, properties):
    if not re.fullmatch(f"v{VERSION}", tag):
        raise ValueError("Expected a version tag such as v0.9.3 or v0.9.4-rc.1")
    versions = re.findall(r"^pluginVersion\s*=\s*(\S+)\s*$", properties, re.MULTILINE)
    if versions != [tag[1:]]:
        raise ValueError("The tag must match pluginVersion in gradle.properties. Tags never override or rebuild it.")
    return tag[1:]


def tag_commit(tag):
    name = f"refs/tags/{tag}"
    try:
        result = subprocess.run(["git", "ls-remote", "--exit-code", "origin", name, f"{name}^{{}}"],
                                capture_output=True, text=True, timeout=30, check=True)
    except subprocess.CalledProcessError as error:
        if error.returncode == 2:
            return None
        raise
    refs = {}
    for line in result.stdout.splitlines():
        value, ref = line.split()
        if ref in refs or ref not in (name, f"{name}^{{}}"):
            raise ValueError("Ambiguous release tag on origin")
        refs[ref] = value
    return refs.get(f"{name}^{{}}", refs.get(name))


def verify_tag_ref(tag, sha):
    if tag_commit(tag) != sha:
        raise ValueError("The release tag moved or no longer identifies this checked-out commit")


class GitHubApi:
    def __init__(self, repository, token, deadline):
        if not re.fullmatch(r"[\w.-]+/[\w.-]+", repository) or not token:
            raise ValueError("GITHUB_REPOSITORY and GH_TOKEN are required")
        self.base = os.environ.get("GITHUB_API_URL", "https://api.github.com").rstrip("/")
        self.repository = repository
        self.token = token
        self.deadline = deadline

    def __call__(self, path, **query):
        url = f"{self.base}/repos/{self.repository}/{path}"
        if query:
            url += "?" + urlencode(query)
        for attempt in range(3):
            remaining = self.deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("Release metadata lookup timed out")
            request = Request(url, headers={
                "Authorization": f"Bearer {self.token}", "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "varro-openjet-release",
            })
            try:
                with urlopen(request, timeout=min(30, remaining)) as response:
                    data = response.read(8 * 1024 * 1024 + 1)
                if len(data) > 8 * 1024 * 1024:
                    raise ValueError("GitHub API response exceeded the release gate's limit")
                return json.loads(data)
            except HTTPError as error:
                retryable = error.code in (429, 500, 502, 503, 504) or (
                    error.code == 403 and error.headers.get("Retry-After") is not None)
                error.close()
                if not retryable or attempt == 2:
                    raise RuntimeError(f"GitHub API GET {path} failed with HTTP {error.code}") from error
            except (URLError, TimeoutError) as error:
                if attempt == 2:
                    raise RuntimeError(f"GitHub API GET {path} failed after three attempts") from error
            time.sleep(min(5, max(0, self.deadline - time.monotonic())))

    def post(self, path, data):
        # Never replay a write. A timeout may already have created a workflow or draft.
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("Release metadata lookup timed out")
        request = Request(f"{self.base}/repos/{self.repository}/{path}",
                          data=json.dumps(data).encode(), method="POST", headers={
                              "Authorization": f"Bearer {self.token}", "Accept": "application/vnd.github+json",
                              "Content-Type": "application/json", "X-GitHub-Api-Version": "2022-11-28",
                              "User-Agent": "varro-openjet-release",
                          })
        try:
            with urlopen(request, timeout=min(30, remaining)) as response:
                body = response.read(1024 * 1024 + 1)
        except HTTPError as error:
            error.close()
            raise RuntimeError(f"GitHub API POST {path} failed with HTTP {error.code}; inspect its result before retrying") from error
        if len(body) > 1024 * 1024:
            raise ValueError("GitHub write response exceeded the release gate's limit; inspect its result before retrying")
        return json.loads(body) if body else None


def trusted_run(run, repository, sha):
    return (run.get("head_sha") == sha and run.get("head_branch") == "main" and
            run.get("event") in ("push", "workflow_dispatch") and run.get("path") == WORKFLOW and
            (run.get("head_repository") or {}).get("full_name") == repository)


def positive_id(value):
    if type(value) is not int or value <= 0:
        raise ValueError("GitHub returned an invalid run, attempt or artifact ID")
    return value


def resolve(repository, sha, api):
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Expected an exact commit SHA")
    runs = api("actions/workflows/ci.yml/runs", head_sha=sha, branch="main", per_page=100)["workflow_runs"]
    candidates = [run for run in runs if trusted_run(run, repository, sha)]
    if not candidates:
        return {"ready": "false", "reason": "CI is not registered yet. Successful main CI completion will restart this release."}
    # Never fall back to an older green run when a newer run/attempt is pending or failed.
    selected_id = max(positive_id(item['id']) for item in candidates)
    run = api(f"actions/runs/{selected_id}")
    if run.get("id") != selected_id or not trusted_run(run, repository, sha):
        raise ValueError("CI run provenance changed")
    if run["status"] != "completed":
        return {"ready": "false", "reason": "CI is still running. Its completion event will restart this release; no runner waits here."}
    if run.get("conclusion") != "success":
        raise ValueError(f"CI run {run['id']} concluded {run.get('conclusion')}. Rerun CI successfully before releasing.")
    run_id = positive_id(run["id"])
    attempt = positive_id(run["run_attempt"])
    expected_name = f"plugin-archive-{attempt}"
    artifacts = []
    for page in range(1, 21):
        batch = api(f"actions/runs/{run_id}/artifacts", per_page=100, page=page)["artifacts"]
        artifacts.extend(item for item in batch if item.get("name") == expected_name)
        if len(batch) < 100:
            break
    else:
        raise ValueError("Too many CI artifacts to safely resolve the release")
    if len(artifacts) != 1 or artifacts[0].get("expired") is not False:
        raise ValueError("The successful CI attempt's archive is missing, expired or ambiguous. Rerun all CI jobs, then retry the release.")
    artifact = artifacts[0]
    artifact_run = artifact.get("workflow_run") or {}
    if artifact_run.get("id") != run_id or artifact_run.get("head_sha") != sha:
        raise ValueError("Artifact provenance does not match the selected CI run and commit")
    current = api(f"actions/runs/{run_id}")
    if current.get("id") != run_id or not trusted_run(current, repository, sha):
        raise ValueError("CI run provenance changed")
    if current.get("run_attempt") != attempt or current.get("status") != "completed":
        return {"ready": "false", "reason": "A new CI attempt started. Its completion event will restart this release."}
    if current.get("conclusion") != "success":
        raise ValueError("CI is no longer successful")
    return {"ready": "true", "run_id": run_id, "artifact_id": positive_id(artifact["id"])}


def find_release(api, tag):
    matches = []
    for page in range(1, 21):
        batch = api("releases", per_page=100, page=page)
        matches.extend(item for item in batch if item.get("tag_name") == tag)
        if len(batch) < 100:
            break
    else:
        raise ValueError("Too many releases to establish unique release ownership")
    if len(matches) > 1:
        raise ValueError("Multiple releases already exist for this tag; inspect them before retrying")
    return matches[0] if matches else None


def wake(repository, sha, ci_run_id, tag, api):
    source = api(f"actions/runs/{positive_id(ci_run_id)}")
    if source.get("id") != ci_run_id or not trusted_run(source, repository, sha):
        raise ValueError("Only successful trusted main CI can restart a tagged release")
    if source.get("status") != "completed" or source.get("conclusion") != "success":
        return {"dispatched": "false", "reason": "The CI result changed after this completion event. A successful new attempt can resume the release."}
    if tag_commit(tag) != sha:
        return {"dispatched": "false", "reason": "There is no matching release tag for this CI commit."}
    if find_release(api, tag):
        return {"dispatched": "false", "reason": "A release already exists or is reserved. Resume its original failed jobs if needed."}
    api.post("actions/workflows/release.yml/dispatches", {"ref": tag})
    return {"dispatched": "true", "tag": tag}


def claim_release(tag, sha, owner, api, require_owned=False, expected_sha256=None):
    marker = f"<!-- varro-openjet-release:{positive_id(owner)}:{sha} -->"
    existing = find_release(api, tag)
    if existing:
        if existing.get("draft") is True and marker in (existing.get("body") or ""):
            return {"ready": "true", "release_id": positive_id(existing["id"])}
        if require_owned:
            assets = [asset for asset in existing.get("assets", []) if asset.get("name") == f"varro-openjet-{tag[1:]}.zip"]
            if (marker in (existing.get("body") or "") and existing.get("draft") is False and
                    expected_sha256 and len(assets) == 1 and assets[0].get("digest") == f"sha256:{expected_sha256}"):
                return {"ready": "false", "reason": "This run already published the exact verified archive."}
            raise ValueError("Release reservation changed, or published bytes cannot be confirmed. Inspect GitHub before retrying.")
        return {"ready": "false", "reason": "Release already published or reserved by another run. Rerun that run's failed jobs, not a new publishing attempt."}
    if require_owned:
        raise ValueError("The release reservation disappeared. Rerun the original release workflow, not publishing alone.")
    created = api.post("releases", {"tag_name": tag, "target_commitish": sha, "name": f"Varro OpenJet {tag}",
                                    "draft": True, "prerelease": "-" in tag, "body": marker})
    if created.get("tag_name") != tag or created.get("draft") is not True or created.get("body") != marker:
        raise ValueError("Cannot confirm release reservation; inspect GitHub before retrying")
    return {"ready": "true", "release_id": positive_id(created["id"])}


def read_member(archive, name, limit):
    entries = [entry for entry in archive.infolist() if entry.filename == name]
    if len(entries) != 1 or entries[0].file_size > limit:
        raise ValueError(f"Missing, duplicate or oversized archive member: {name}")
    return archive.read(entries[0])


def check_archive(directory, version, expected_sha256=None):
    archives = list(directory.glob("*.zip"))
    expected_name = f"varro-openjet-{version}.zip"
    if len(archives) != 1 or archives[0].name != expected_name or archives[0].is_symlink():
        raise ValueError(f"Expected exactly one tested archive named {expected_name}")
    archive = archives[0]
    if archive.stat().st_size > 256 * 1024 * 1024:
        raise ValueError("Plugin archive exceeds the release gate's limit")
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    if expected_sha256 is not None and (not re.fullmatch(r"[0-9a-f]{64}", expected_sha256) or digest != expected_sha256):
        raise ValueError("Plugin archive checksum differs from the selected CI archive")
    with zipfile.ZipFile(archive) as outer:
        jar_bytes = read_member(outer, f"varro-openjet/lib/varro-openjet-{version}.jar", 128 * 1024 * 1024)
    with zipfile.ZipFile(io.BytesIO(jar_bytes)) as jar:
        descriptor = ET.fromstring(read_member(jar, "META-INF/plugin.xml", 1024 * 1024))
    if descriptor.findtext("id") != PLUGIN_ID or descriptor.findtext("version") != version:
        raise ValueError("Packaged plugin ID/version does not match the release tag")
    return {"archive": str(archive), "sha256": digest}


def emit(values):
    for key, value in values.items():
        print(f"{key}={value}")
    if output := os.environ.get("GITHUB_OUTPUT"):
        with open(output, "a") as stream:
            for key, value in values.items():
                stream.write(f"{key}={value}\n")
    if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(summary, "a") as stream:
            stream.write(values.get("reason", "Release handoff completed.") + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    select = commands.add_parser("resolve")
    select.add_argument("--sha", required=True)
    restart = commands.add_parser("wake")
    restart.add_argument("--sha", required=True)
    restart.add_argument("--ci-run-id", type=int, required=True)
    claim = commands.add_parser("claim")
    claim.add_argument("--sha", required=True)
    claim.add_argument("--require-owned", action="store_true")
    claim.add_argument("--expected-sha256")
    check = commands.add_parser("check")
    check.add_argument("--directory", type=Path, default=Path("dist"))
    check.add_argument("--expected-sha256")
    check.add_argument("--verify-tag-ref", action="store_true", help="Reject a release tag moved since this workflow started")
    for command in (select, check, claim):
        command.add_argument("--tag", required=True)
    args = parser.parse_args()
    try:
        properties = (ROOT / "gradle.properties").read_text()
        if args.command == "wake":
            versions = re.findall(r"^pluginVersion\s*=\s*(\S+)\s*$", properties, re.MULTILINE)
            if len(versions) != 1:
                raise ValueError("Expected one committed pluginVersion")
            args.tag = "v" + versions[0]
        version = release_version(args.tag, properties)
        if args.command in ("resolve", "wake", "claim"):
            if not re.fullmatch(r"[0-9a-f]{40}", args.sha):
                raise ValueError("Expected an exact commit SHA")
            repository = os.environ.get("GITHUB_REPOSITORY", "")
            api = GitHubApi(repository, os.environ.get("GH_TOKEN", ""), time.monotonic() + 120)
            if args.command == "wake":
                emit(wake(repository, args.sha, args.ci_run_id, args.tag, api))
            else:
                verify_tag_ref(args.tag, args.sha)
                if args.command == "resolve":
                    emit(resolve(repository, args.sha, api))
                else:
                    if args.require_owned and not re.fullmatch(r"[0-9a-f]{64}", args.expected_sha256 or ""):
                        raise ValueError("Publishing requires the verified archive checksum")
                    emit(claim_release(args.tag, args.sha, int(os.environ["GITHUB_RUN_ID"]), api,
                                       args.require_owned, args.expected_sha256))
        else:
            if args.verify_tag_ref:
                sha = subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True, timeout=10, check=True).stdout.strip()
                verify_tag_ref(args.tag, sha)
            emit(check_archive(args.directory, version, args.expected_sha256))
        return 0
    except (KeyError, TypeError, OSError, ValueError, RuntimeError, ET.ParseError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        print(f"Release blocked: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
