#!/usr/bin/env python3
"""Check Docker cleanup scope and failure handling without touching Docker data."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


class BuildScriptTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "scripts").mkdir()
        shutil.copy(Path(__file__).with_name("build.sh"), self.root / "scripts/build.sh")
        (self.root / "webview/vendor/webview").mkdir(parents=True)
        for name in ("Dockerfile", ".dockerignore", "webview/package.json", "webview/package-lock.json"):
            (self.root / name).touch()
        (self.root / "bin").mkdir()
        docker = self.root / "bin/docker"
        docker.write_text(f"#!{sys.executable}\n" + r'''
import json, os, sys
from pathlib import Path
args = sys.argv[1:]
with open("docker-calls.jsonl", "a") as log:
    log.write(json.dumps(args) + "\n")
if args[:2] == ["buildx", "inspect"]:
    if "--format" in args:
        print("unknown flag: --format", file=sys.stderr)
        sys.exit(125)
    if os.environ.get("DRIVER"):
        driver = os.environ["DRIVER"]
    elif Path("builder-created").exists():
        driver = "docker-container"
    else:
        sys.exit(1)
    print("Name:          varro-openjet")
    print("Driver:        " + driver)
    print("\nNodes:\nName:          varro-openjet0")
elif args[:2] == ["buildx", "create"]:
    if os.environ.get("DRIVER") or Path("builder-created").exists():
        print('existing instance for "varro-openjet" but no append mode', file=sys.stderr)
        sys.exit(1)
    Path("builder-created").touch()
elif args[:2] == ["image", "inspect"]:
    print("outdated")
elif args[:2] in (["image", "prune"], ["buildx", "prune"]):
    sys.exit(int(os.environ.get("PRUNE_EXIT", "0")))
elif args[0] == "compose" and "build" in args:
    sys.exit(int(os.environ.get("BUILD_EXIT", "0")))
elif args[:2] == ["compose", "run"]:
    sys.exit(int(os.environ.get("RUN_EXIT", "0")))
''')
        docker.chmod(0o755)

    def run_build(self, *args, **env):
        result = subprocess.run(
            ["bash", "scripts/build.sh", *args], cwd=self.root,
            env={**os.environ, "PATH": f"{self.root / 'bin'}:{os.environ['PATH']}", **env},
            capture_output=True, text=True,
        )
        calls = [json.loads(line) for line in (self.root / "docker-calls.jsonl").read_text().splitlines()]
        for call in calls:
            if "prune" not in call:
                continue
            if call[:2] == ["image", "prune"]:
                self.assertEqual(call, ["image", "prune", "--force", "--filter", "label=io.varro.build-env-hash"])
            else:
                self.assertEqual(call, ["buildx", "prune", "--builder", "varro-openjet", "--force",
                                       "--max-used-space", "4GB", "--reserved-space", "1GB", "--min-free-space", "10GB"])
        return result, calls

    def test_build_uses_isolated_builder_and_cleans_afterwards(self):
        result, calls = self.run_build()
        self.assertEqual(result.returncode, 0, result.stderr)
        create = next(call for call in calls if call[:2] == ["buildx", "create"])
        self.assertIn("default-load=true", create)
        self.assertNotIn("--use", create)
        self.assertIn(["compose", "--progress", "plain", "build", "--builder", "varro-openjet", "shell"], calls)
        self.assertIn(["compose", "run", "--rm", "dev"], calls)
        self.assertEqual(calls[-1][:4], ["buildx", "prune", "--builder", "varro-openjet"])

    def test_failures_keep_exit_status_and_still_cleanup(self):
        for failure in ("BUILD_EXIT", "RUN_EXIT"):
            with self.subTest(failure=failure):
                result, calls = self.run_build(**{failure: "42", "PRUNE_EXIT": "3"})
                self.assertEqual(result.returncode, 42, result.stderr)
                self.assertEqual(calls[-1][:2], ["buildx", "prune"])

    def test_existing_builder_is_reused_without_unsupported_inspect_flags(self):
        result, calls = self.run_build(DRIVER="docker-container")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(["buildx", "inspect", "varro-openjet"], calls)
        self.assertFalse(any(call[:2] == ["buildx", "create"] for call in calls))
        self.assertIn(["compose", "--progress", "plain", "build", "--builder", "varro-openjet", "shell"], calls)
        self.assertIn(["compose", "run", "--rm", "dev"], calls)
        self.assertEqual(sum(call[:2] == ["buildx", "prune"] for call in calls), 2)

    def test_prune_without_builder_does_not_create_or_build(self):
        result, calls = self.run_build("prune")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(any(call[:2] in (["buildx", "create"], ["buildx", "prune"], ["compose", "run"])
                             for call in calls))

    def test_prune_existing_builder_does_not_build(self):
        result, calls = self.run_build("prune", DRIVER="docker-container")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls[-1][:4], ["buildx", "prune", "--builder", "varro-openjet"])
        self.assertFalse(any(call[0] == "compose" and "build" in call for call in calls))

    def test_wrong_driver_is_rejected_before_cleanup(self):
        result, calls = self.run_build(DRIVER="docker")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(any("prune" in call for call in calls))

    def test_gradle_arguments_are_forwarded_without_shell_interpolation(self):
        result, calls = self.run_build("gradle", "help", "-Pname=contains spaces; literal")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(["compose", "run", "--rm", "shell", "./gradlew", "--no-daemon",
                       "help", "-Pname=contains spaces; literal"], calls)


if __name__ == "__main__":
    unittest.main()
