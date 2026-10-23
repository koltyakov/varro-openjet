#!/usr/bin/env python3
"""Release gate regressions. All API responses and plugin archives are fixtures."""

import copy
import importlib.util
import io
import os
import subprocess
import sys
import tempfile
import time
import unittest
import zipfile
from collections import deque
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("release", Path(__file__).with_name("release-artifact.py"))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
REPOSITORY = "fixture/openjet"
SHA = "a" * 40


def run(**overrides):
    return {"id": 100, "head_sha": SHA, "head_branch": "main", "event": "push",
            "path": release.WORKFLOW, "head_repository": {"full_name": REPOSITORY},
            "status": "completed", "conclusion": "success", "run_attempt": 1, **overrides}


def artifact(**overrides):
    return {"id": 200, "name": "plugin-archive-1", "expired": False,
            "workflow_run": {"id": 100, "head_sha": SHA}, **overrides}


class Api:
    def __init__(self, runs=None, details=None, artifacts=None, releases=None):
        self.runs = runs if runs is not None else [run()]
        self.details = deque(details or [run()])
        self.artifacts = artifacts if artifacts is not None else [artifact()]
        self.calls = []
        self.releases = releases or []
        self.posts = []

    def __call__(self, path, **query):
        self.calls.append((path, query))
        if path == "releases":
            return copy.deepcopy(self.releases)
        if path.endswith("/runs"):
            return {"workflow_runs": copy.deepcopy(self.runs)}
        if path.endswith("/artifacts"):
            return {"artifacts": copy.deepcopy(self.artifacts)}
        if len(self.details) > 1:
            return copy.deepcopy(self.details.popleft())
        return copy.deepcopy(self.details[0])

    def post(self, path, data):
        self.posts.append((path, copy.deepcopy(data)))
        if path == "releases":
            created = {"id": 300, **data}
            self.releases.append(created)
            return copy.deepcopy(created)
        return None


class ReleaseSelectionTest(unittest.TestCase):
    def resolve(self, api):
        return release.resolve(REPOSITORY, SHA, api)

    def test_success_pins_an_immutable_artifact_and_attempt_for_the_exact_commit(self):
        api = Api()
        self.assertEqual(self.resolve(api), {"ready": "true", "run_id": 100, "artifact_id": 200})
        self.assertEqual(api.calls[0], ("actions/workflows/ci.yml/runs", {"head_sha": SHA, "branch": "main", "per_page": 100}))
        self.assertEqual(sum(path == "actions/runs/100" for path, _ in api.calls), 2)

    def test_early_tags_exit_without_polling_or_sleeping(self):
        for api in (Api(runs=[]), Api(details=[run(status="queued")]), Api(details=[run(status="in_progress")])):
            with self.subTest(api=api), patch.object(release.time, "sleep", side_effect=AssertionError("No CI waiting")):
                self.assertEqual(self.resolve(api)["ready"], "false")
                self.assertEqual(sum(path.endswith("/runs") for path, _ in api.calls), 1)
                self.assertFalse(any(path.endswith("/artifacts") for path, _ in api.calls))
                self.assertEqual(api.posts, [])

    def test_latest_failed_or_cancelled_run_never_falls_back_to_an_older_green_run(self):
        for conclusion in ("failure", "cancelled", "timed_out", "skipped", "neutral", None):
            with self.subTest(conclusion=conclusion):
                api = Api(runs=[run(id=99), run()], details=[run(conclusion=conclusion)])
                with self.assertRaisesRegex(ValueError, "concluded"):
                    self.resolve(api)
                self.assertFalse(any(path.endswith("/artifacts") for path, _ in api.calls))

    def test_wrong_commit_repo_branch_event_and_workflow_are_never_release_sources(self):
        for change in ({"head_sha": "b" * 40}, {"head_branch": "feature"}, {"event": "pull_request"},
                       {"path": ".github/workflows/other.yml"}, {"head_repository": {"full_name": "foreign/repo"}}, {"head_repository": None}):
            with self.subTest(change=change):
                api = Api(runs=[run(**change)])
                self.assertEqual(self.resolve(api)["ready"], "false")
                self.assertEqual(len(api.calls), 1)

    def test_manual_main_ci_can_recreate_an_expired_archive(self):
        manual = run(event="workflow_dispatch")
        self.assertEqual(self.resolve(Api(runs=[manual], details=[manual])), {"ready": "true", "run_id": 100, "artifact_id": 200})

    def test_newer_pending_ci_does_not_fall_back_to_an_older_green_run(self):
        api = Api(runs=[run(id=99), run()], details=[run(status="queued")])
        self.assertEqual(self.resolve(api)["ready"], "false")
        self.assertEqual(api.calls[-1][0], "actions/runs/100")

    def test_missing_expired_ambiguous_or_old_attempt_artifacts_fail_closed(self):
        for artifacts in ([], [artifact(expired=True)], [artifact(), artifact(id=201)],
                          [artifact(name="plugin-archive")], [artifact(name="plugin-archive-2")]):
            with self.subTest(artifacts=artifacts), self.assertRaisesRegex(ValueError, "missing, expired or ambiguous"):
                self.resolve(Api(artifacts=artifacts))

    def test_artifact_provenance_must_match_run_and_commit(self):
        for identity in ({"id": 101, "head_sha": SHA}, {"id": 100, "head_sha": "b" * 40}, {}, None):
            with self.subTest(identity=identity), self.assertRaisesRegex(ValueError, "Artifact provenance"):
                self.resolve(Api(artifacts=[artifact(workflow_run=identity)]))

    def test_ci_rerun_during_selection_cannot_release_the_previous_attempt(self):
        api = Api(details=[run(), run(run_attempt=2, status="in_progress"), run(run_attempt=2)])
        self.assertEqual(self.resolve(api)["ready"], "false")
        self.assertEqual(sum(path.endswith("/runs") for path, _ in api.calls), 1)

    def test_changed_run_provenance_and_invalid_ids_are_rejected(self):
        for details in ([run(head_sha="b" * 40)], [run(id=999)], [run(run_attempt=0)],
                        [run(), run(head_branch="foreign")]):
            with self.subTest(details=details), self.assertRaises(ValueError):
                self.resolve(Api(details=details))
        for value in (0, -1, "100", True):
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.resolve(Api(runs=[run(id=value)]))

    def test_malformed_commit_is_rejected_before_api_access(self):
        api = Api()
        with self.assertRaises(ValueError):
            release.resolve(REPOSITORY, "main", api)
        self.assertEqual(api.calls, [])

    def test_artifact_search_handles_multiple_pages(self):
        api = Api()

        def paginated(path, **query):
            if path.endswith("/artifacts") and query["page"] == 1:
                return {"artifacts": [artifact(id=index + 1, name=f"unrelated-{index}") for index in range(100)]}
            return api(path, **query)

        self.assertEqual(self.resolve(paginated), {"ready": "true", "run_id": 100, "artifact_id": 200})
        self.assertIn(("actions/runs/100/artifacts", {"per_page": 100, "page": 2}), api.calls)


class HandoffTest(unittest.TestCase):
    def wake(self, api):
        return release.wake(REPOSITORY, SHA, 100, "v0.9.3", api)

    def test_tag_first_then_ci_completion_dispatches_once_and_claim_prevents_duplicate_work(self):
        api = Api(details=[run(status="in_progress")])
        self.assertEqual(release.resolve(REPOSITORY, SHA, api)["ready"], "false")
        api.details = deque([run()])
        with patch.object(release, "tag_commit", return_value=SHA):
            self.assertEqual(self.wake(api)["dispatched"], "true")
        self.assertEqual(api.posts, [("actions/workflows/release.yml/dispatches", {"ref": "v0.9.3"})])
        self.assertEqual(release.resolve(REPOSITORY, SHA, api)["ready"], "true")
        self.assertEqual(release.claim_release("v0.9.3", SHA, 900, api)["ready"], "true")
        self.assertEqual(release.claim_release("v0.9.3", SHA, 901, api)["ready"], "false")
        with patch.object(release, "tag_commit", return_value=SHA):
            self.assertEqual(self.wake(api)["dispatched"], "false")
        self.assertEqual(len(api.posts), 2)

    def test_ci_first_does_not_dispatch_without_a_tag_and_later_tag_can_start(self):
        api = Api()
        with patch.object(release, "tag_commit", return_value=None):
            self.assertEqual(self.wake(api)["dispatched"], "false")
        self.assertEqual(api.posts, [])
        self.assertEqual(release.resolve(REPOSITORY, SHA, api)["ready"], "true")
        self.assertEqual(release.claim_release("v0.9.3", SHA, 900, api)["ready"], "true")

    def test_untrusted_failed_and_moved_sources_cannot_dispatch(self):
        for source in (run(event="pull_request"), run(head_branch="feature"), run(head_sha="b" * 40),
                       run(head_repository={"full_name": "foreign/repo"})):
            api = Api(details=[source])
            with self.subTest(source=source), patch.object(release, "tag_commit") as tag, self.assertRaises(ValueError):
                self.wake(api)
            tag.assert_not_called()
            self.assertEqual(api.posts, [])
        for source in (run(conclusion="failure"), run(status="queued")):
            api = Api(details=[source])
            with self.subTest(source=source), patch.object(release, "tag_commit") as tag:
                self.assertEqual(self.wake(api)["dispatched"], "false")
            tag.assert_not_called()
            self.assertEqual(api.posts, [])
        api = Api()
        with patch.object(release, "tag_commit", return_value="b" * 40):
            self.assertEqual(self.wake(api)["dispatched"], "false")
        self.assertEqual(api.posts, [])

    def test_reservation_is_a_draft_and_failed_original_run_can_resume_without_new_reservation(self):
        api = Api()
        self.assertEqual(release.claim_release("v0.9.3-rc.1", SHA, 900, api), {"ready": "true", "release_id": 300})
        created = api.posts[0][1]
        self.assertIs(created["draft"], True)
        self.assertIs(created["prerelease"], True)
        self.assertEqual(created["target_commitish"], SHA)
        self.assertIn(f":900:{SHA}", created["body"])
        self.assertEqual(release.claim_release("v0.9.3-rc.1", SHA, 900, api)["ready"], "true")
        self.assertEqual(len(api.posts), 1)

    def test_published_manual_and_other_run_reservations_never_auto_retry_publishing(self):
        for existing in ({"id": 300, "tag_name": "v0.9.3", "draft": False},
                         {"id": 300, "tag_name": "v0.9.3", "draft": True, "body": "Manual draft"},
                         {"id": 300, "tag_name": "v0.9.3", "draft": True, "body": f"<!-- varro-openjet-release:899:{SHA} -->"}):
            api = Api(releases=[existing])
            with self.subTest(existing=existing):
                self.assertEqual(release.claim_release("v0.9.3", SHA, 900, api)["ready"], "false")
                with self.assertRaises(ValueError):
                    release.claim_release("v0.9.3", SHA, 900, api, require_owned=True)
                self.assertEqual(api.posts, [])

    def test_publication_recovery_requires_same_owner_and_exact_asset_digest(self):
        api = Api()
        release.claim_release("v0.9.3", SHA, 900, api)
        api.releases[0].update(draft=False, assets=[{"name": "varro-openjet-0.9.3.zip", "digest": "sha256:" + "c" * 64}])
        self.assertEqual(release.claim_release("v0.9.3", SHA, 900, api, True, "c" * 64)["ready"], "false")
        for digest in (None, "d" * 64):
            with self.subTest(digest=digest), self.assertRaises(ValueError):
                release.claim_release("v0.9.3", SHA, 900, api, True, digest)
        self.assertEqual(len(api.posts), 1)

    def test_lost_or_duplicate_reservations_fail_closed_before_publishing(self):
        api = Api()
        with self.assertRaises(ValueError):
            release.claim_release("v0.9.3", SHA, 900, api, require_owned=True)
        existing = {"id": 300, "tag_name": "v0.9.3", "draft": True}
        api.releases = [existing, existing]
        with self.assertRaisesRegex(ValueError, "Multiple releases"):
            release.claim_release("v0.9.3", SHA, 900, api)
        self.assertEqual(api.posts, [])


class TagRefTest(unittest.TestCase):
    def test_lightweight_and_annotated_tags_resolve_to_the_same_checked_out_commit(self):
        for output in (f"{SHA}\trefs/tags/v0.9.3\n",
                       f"{'b' * 40}\trefs/tags/v0.9.3\n{SHA}\trefs/tags/v0.9.3^{{}}\n"):
            with self.subTest(output=output), patch.object(release.subprocess, "run") as command:
                command.return_value.stdout = output
                release.verify_tag_ref("v0.9.3", SHA)
                self.assertEqual(command.call_args.args[0], ["git", "ls-remote", "--exit-code", "origin",
                                                            "refs/tags/v0.9.3", "refs/tags/v0.9.3^{}"])
                self.assertEqual(command.call_args.kwargs["timeout"], 30)

    def test_moved_deleted_and_ambiguous_tags_are_rejected(self):
        for output in ("", f"{'b' * 40}\trefs/tags/v0.9.3\n",
                       f"{SHA}\trefs/tags/v0.9.3\n{SHA}\trefs/tags/v0.9.3\n"):
            with self.subTest(output=output), patch.object(release.subprocess, "run") as command:
                command.return_value.stdout = output
                with self.assertRaises(ValueError):
                    release.verify_tag_ref("v0.9.3", SHA)


class GitHubApiTest(unittest.TestCase):
    def api(self, deadline=None):
        return release.GitHubApi(REPOSITORY, "fixture-secret", deadline or time.monotonic() + 60)

    def test_requests_are_get_only_with_auth_in_headers_and_bounded_reads(self):
        response = unittest.mock.MagicMock()
        response.__enter__.return_value.read.return_value = b'{"workflow_runs": []}'
        with patch.object(release, "urlopen", return_value=response) as request:
            self.assertEqual(self.api()("actions/workflows/ci.yml/runs", head_sha=SHA), {"workflow_runs": []})
        sent = request.call_args.args[0]
        self.assertEqual(sent.get_method(), "GET")
        self.assertEqual(sent.get_header("Authorization"), "Bearer fixture-secret")
        self.assertNotIn("fixture-secret", sent.full_url)
        self.assertLessEqual(request.call_args.kwargs["timeout"], 30)
        response.__enter__.return_value.read.assert_called_once_with(8 * 1024 * 1024 + 1)

    def test_transient_api_errors_retry_but_auth_errors_do_not(self):
        for code, attempts in ((503, 3), (429, 3), (401, 1), (403, 1), (404, 1)):
            with self.subTest(code=code):
                error = release.HTTPError("https://fixture.invalid", code, "fixture failure", {}, None)
                with (
                    patch.object(release, "urlopen", side_effect=error) as request,
                    patch.object(release.time, "sleep"),
                    self.assertRaisesRegex(RuntimeError, f"HTTP {code}") as raised,
                ):
                    self.api()("actions/runs/100")
                self.assertEqual(request.call_count, attempts)
                self.assertNotIn("fixture-secret", str(raised.exception))

    def test_deadline_and_missing_credentials_fail_without_requests(self):
        with patch.object(release, "urlopen") as request:
            with self.assertRaises(TimeoutError):
                self.api(deadline=time.monotonic() - 1)("actions/runs/100")
            for repository, token in ((REPOSITORY, ""), ("../foreign/repo", "fixture-secret")):
                with self.assertRaises(ValueError):
                    release.GitHubApi(repository, token, time.monotonic() + 1)
        request.assert_not_called()

    def test_dispatch_post_is_explicit_and_accepts_an_empty_204_response(self):
        response = unittest.mock.MagicMock()
        response.__enter__.return_value.read.return_value = b""
        with patch.object(release, "urlopen", return_value=response) as request:
            self.assertIsNone(self.api().post("actions/workflows/release.yml/dispatches", {"ref": "v0.9.3"}))
        sent = request.call_args.args[0]
        self.assertEqual(sent.get_method(), "POST")
        self.assertEqual(release.json.loads(sent.data), {"ref": "v0.9.3"})
        self.assertEqual(request.call_count, 1)

    def test_uncertain_writes_never_retry_or_create_duplicate_releases(self):
        for error in (release.HTTPError("https://fixture.invalid", 503, "fixture failure", {}, None),
                      TimeoutError("uncertain write")):
            with (
                self.subTest(error=error),
                patch.object(release, "urlopen", side_effect=error) as request,
                patch.object(release.time, "sleep") as sleep,
                self.assertRaises((RuntimeError, TimeoutError)),
            ):
                self.api().post("releases", {"tag_name": "v0.9.3", "draft": True})
            self.assertEqual(request.call_count, 1)
            sleep.assert_not_called()


class ArchiveTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)

    def archive(self, version="0.9.3", plugin_id=release.PLUGIN_ID, descriptor=None):
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, "w") as inner:
            inner.writestr("META-INF/plugin.xml", descriptor or
                           f"<idea-plugin><id>{plugin_id}</id><version>{version}</version></idea-plugin>")
        path = self.directory / "varro-openjet-0.9.3.zip"
        with zipfile.ZipFile(path, "w") as outer:
            outer.writestr("varro-openjet/lib/varro-openjet-0.9.3.jar", jar.getvalue())
        return path

    def test_tag_must_match_the_committed_version(self):
        for tag in ("v0.9.3", "v0.9.3-rc.1"):
            self.assertEqual(release.release_version(tag, f"pluginVersion={tag[1:]}\n"), tag[1:])
        for tag in ("0.9.3", "v0.9", "v0.9.3\nother", "v0.9.3;echo"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                release.release_version(tag, "pluginVersion=0.9.3\n")
        for properties in ("pluginVersion=0.9.2\n", "", "pluginVersion=0.9.3\npluginVersion=0.9.3\n"):
            with self.subTest(properties=properties), self.assertRaises(ValueError):
                release.release_version("v0.9.3", properties)

    def test_archive_identity_and_hash_are_validated_without_extraction(self):
        archive = self.archive()
        result = release.check_archive(self.directory, "0.9.3")
        self.assertEqual(result["archive"], str(archive))
        self.assertEqual(release.check_archive(self.directory, "0.9.3", result["sha256"]), result)
        self.assertEqual(list(self.directory.iterdir()), [archive])

    def test_same_filename_with_wrong_plugin_or_version_is_rejected(self):
        for options in ({"version": "0.9.2"}, {"plugin_id": "foreign.plugin"}):
            with self.subTest(options=options):
                self.archive(**options)
                with self.assertRaisesRegex(ValueError, "ID/version"):
                    release.check_archive(self.directory, "0.9.3")

    def test_tampering_or_malformed_expected_hash_is_rejected(self):
        archive = self.archive()
        digest = release.check_archive(self.directory, "0.9.3")["sha256"]
        with archive.open("ab") as stream:
            stream.write(b"changed")
        for expected in (digest, "", "not a sha", "a" * 63):
            with self.subTest(expected=expected), self.assertRaisesRegex(ValueError, "checksum"):
                release.check_archive(self.directory, "0.9.3", expected)

    def test_missing_extra_wrong_named_and_symlinked_archives_are_rejected(self):
        with self.assertRaises(ValueError):
            release.check_archive(self.directory, "0.9.3")
        archive = self.archive()
        other = self.directory / "other.zip"
        other.write_bytes(archive.read_bytes())
        with self.assertRaises(ValueError):
            release.check_archive(self.directory, "0.9.3")
        archive.unlink()
        with self.assertRaises(ValueError):
            release.check_archive(self.directory, "0.9.3")
        archive.symlink_to(other)
        with self.assertRaises(ValueError):
            release.check_archive(self.directory, "0.9.3")

    def test_invalid_zip_xml_and_missing_or_duplicate_members_are_rejected(self):
        self.archive(descriptor="<bad")
        with self.assertRaises(release.ET.ParseError):
            release.check_archive(self.directory, "0.9.3")
        archive = self.directory / "varro-openjet-0.9.3.zip"
        archive.write_bytes(b"not a zip")
        with self.assertRaises(zipfile.BadZipFile):
            release.check_archive(self.directory, "0.9.3")
        with zipfile.ZipFile(archive, "w") as outer:
            outer.writestr("unrelated.jar", b"fixture")
        with self.assertRaisesRegex(ValueError, "Missing, duplicate or oversized"):
            release.check_archive(self.directory, "0.9.3")
        with zipfile.ZipFile(io.BytesIO(), "w") as fixture:
            fixture.writestr("duplicate", b"one")
            with self.assertWarns(UserWarning):
                fixture.writestr("duplicate", b"two")
            with self.assertRaises(ValueError):
                release.read_member(fixture, "duplicate", 100)
            with self.assertRaises(ValueError):
                release.read_member(fixture, "duplicate", 1)

    def test_cli_fails_without_network_or_publishing_when_tag_is_invalid(self):
        result = subprocess.run([sys.executable, str(Path(release.__file__)), "resolve", "--tag", "invalid", "--sha", SHA],
                                capture_output=True, text=True, check=False, env={**os.environ, "GH_TOKEN": ""})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Release blocked", result.stderr)

    def test_outputs_are_emitted_for_github_actions(self):
        output = self.directory / "output"
        with patch.dict(os.environ, GITHUB_OUTPUT=str(output)), patch("sys.stdout", io.StringIO()):
            release.emit({"run_id": 100, "artifact_id": 200})
        self.assertEqual(output.read_text(), "run_id=100\nartifact_id=200\n")


if __name__ == "__main__":
    unittest.main()
