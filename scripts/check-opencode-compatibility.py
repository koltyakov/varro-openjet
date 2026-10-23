#!/usr/bin/env python3
"""Run isolated released V2 checks, optionally with the full CI build."""

import argparse
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SUITES = (
    "varro.server.OpenCodeV2IntegrationTest",
    "varro.server.OpenCodeManagedServiceIntegrationTest",
)


def released_version(value):
    if not re.fullmatch(r"2\.\d+\.\d+", value):
        raise argparse.ArgumentTypeError("Use an exact released V2 version, such as 2.1.1")
    return value


def cli_version(output):
    match = re.fullmatch(r"(?:opencode v)?(2\.\d+\.\d+)", output.strip())
    if not match:
        raise ValueError(f"Unexpected OpenCode version output: {output.strip()!r}")
    return match.group(1)


def read_results(directory):
    results = []
    for name in SUITES:
        path = directory / f"TEST-{name}.xml"
        suite = ET.parse(path).getroot()
        counts = {key: int(suite.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}
        if suite.get("name") != name or counts["tests"] == 0 or any(counts[key] for key in ("failures", "errors", "skipped")):
            raise ValueError(f"Compatibility suite did not pass without skips: {name}: {counts}")
        results.append({"suite": name, **counts})
    return results


def install_binary(version, directory):
    npm = "npm.cmd" if os.name == "nt" else "npm"
    subprocess.run([
        npm, "install", "--prefix", str(directory), "--ignore-scripts", "--no-save",
        "--package-lock=false", "--no-audit", "--no-fund", f"@opencode/cli@{version}",
    ], cwd=ROOT, check=True)
    package = directory / "node_modules/@opencode/cli"
    manifest = json.loads((package / "package.json").read_text())
    if manifest["version"] != version:
        raise ValueError("The installed OpenCode package does not match the requested release")
    # Explicitly run only the pinned CLI's binary installer, without changing npm's global script policy.
    subprocess.run(["node", str(package / "postinstall.mjs")], cwd=ROOT, check=True)
    return (package / manifest["bin"]["opencode"]).resolve()


def probe_environment(directory):
    directory.mkdir(parents=True, exist_ok=True)
    environment = {key: value for key, value in os.environ.items() if key.upper() in ("PATH", "SYSTEMROOT")}
    environment.update({
        "HOME": str(directory), "USERPROFILE": str(directory),
        "LOCALAPPDATA": str(directory / "local"), "APPDATA": str(directory / "appdata"),
        "TMPDIR": str(directory), "TEMP": str(directory),
        "XDG_CONFIG_HOME": str(directory / "config"), "XDG_DATA_HOME": str(directory / "data"),
        "XDG_STATE_HOME": str(directory / "state"), "XDG_CACHE_HOME": str(directory / "cache"),
        "OPENCODE_DB": str(directory / "probe.db"), "VARRO_TEST_STATE_ROOT": str(directory),
    })
    return environment


def gradle_command(full_check=False):
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if full_check:
        # One invocation runs the full suite with the pinned binary, static checks and packaging.
        return [str(wrapper), "--no-daemon", "--stacktrace", "check", "buildPlugin", "--rerun-tasks"]
    command = [str(wrapper), "test", "-PskipWebview=true", "--rerun-tasks"]
    for name in SUITES:
        command += ["--tests", name]
    return command


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", type=released_version, required=True)
    parser.add_argument("--binary", type=Path, help="Use an existing matching executable instead of installing one")
    parser.add_argument("--full-check", action="store_true", help="Run the full CI check and package once with the released suites enabled")
    args = parser.parse_args()
    directory = ROOT / "build/opencode-compatibility" / args.version
    directory.mkdir(parents=True, exist_ok=True)
    record = {"version": args.version, "status": "failed"}
    try:
        binary = args.binary.resolve(strict=True) if args.binary else install_binary(args.version, directory)
        probe = subprocess.run([str(binary), "--version"], cwd=directory,
                               env=probe_environment(directory / "probe"), capture_output=True, text=True,
                               timeout=15, check=True)
        observed = cli_version(probe.stdout)
        if observed != args.version:
            raise ValueError(f"OpenCode binary is {observed}, expected {args.version}")
        record.update(binary=str(binary), observedVersion=observed)
        environment = dict(os.environ)
        environment.update(VARRO_OPENCODE_TEST_BINARY=str(binary), VARRO_OPENCODE_TEST_VERSION=args.version,
                           VARRO_OPENCODE_DESKTOP_TEST_BINARY=str(binary))
        command = gradle_command(args.full_check)
        record["command"] = command
        subprocess.run(command, cwd=ROOT, env=environment, timeout=2400 if args.full_check else 600, check=True)
        record.update(status="passed", suites=read_results(ROOT / "build/test-results/test"))
        print(f"Kotlin adapter and managed-service compatibility passed against OpenCode {observed}.")
        return 0
    except (OSError, ValueError, ET.ParseError, subprocess.SubprocessError) as error:
        record["error"] = str(error)
        print(f"OpenCode compatibility failed: {error}", file=sys.stderr)
        return 1
    finally:
        path = directory / "verification.json"
        path.write_text(json.dumps(record, indent=2) + "\n")
        print(f"Compatibility evidence: {path}")


if __name__ == "__main__":
    sys.exit(main())
