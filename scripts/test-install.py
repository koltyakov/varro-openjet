#!/usr/bin/env python3
"""Check artifact selection using isolated IDE profiles, without touching real IDEs."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile


class InstallScriptTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "scripts").mkdir()
        shutil.copy(Path(__file__).with_name("install.sh"), self.root / "scripts/install.sh")
        (self.root / "scripts/build.sh").write_text("#!/usr/bin/env bash\nexit 0\n")
        (self.root / "scripts/build.sh").chmod(0o755)
        (self.root / "gradle.properties").write_text("pluginVersion=0.7.12\npluginSinceBuild=252\n")
        (self.root / "dist").mkdir()
        self.home = self.root / "home"
        if os.uname().sysname == "Darwin":
            config = self.home / "Library/Application Support/JetBrains"
        else:
            config = self.home / ".local/share/JetBrains"
        self.plugins = config / "IntelliJIdea2026.2/plugins"
        self.plugins.mkdir(parents=True)

    def artifact(self, version, timestamp):
        path = self.root / f"dist/varro-openjet-{version}.zip"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("varro-openjet/version.txt", version)
        os.utime(path, (timestamp, timestamp))

    def run_install(self, *args):
        return subprocess.run(
            ["bash", "scripts/install.sh", *args], cwd=self.root,
            env={**os.environ, "HOME": str(self.home)}, capture_output=True, text=True,
        )

    def test_current_version_wins_over_newer_timestamp(self):
        self.artifact("0.7.12", 1000)
        self.artifact("0.7.9", 2000)
        for args in ((), ("--no-build",)):
            with self.subTest(args=args):
                result = self.run_install(*args)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn("==> Artifact: dist/varro-openjet-0.7.12.zip", result.stdout)
                self.assertEqual((self.plugins / "varro-openjet/version.txt").read_text(), "0.7.12")

    def test_missing_current_artifact_preserves_installed_plugin(self):
        self.artifact("0.7.9", 2000)
        installed = self.plugins / "varro-openjet/version.txt"
        installed.parent.mkdir()
        installed.write_text("existing plugin")
        result = self.run_install("--no-build")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("missing dist/varro-openjet-0.7.12.zip", result.stderr)
        self.assertEqual(installed.read_text(), "existing plugin")

    def test_missing_version_is_rejected(self):
        (self.root / "gradle.properties").write_text("pluginSinceBuild=252\n")
        self.artifact("0.7.9", 2000)
        result = self.run_install("--no-build")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("pluginVersion is missing", result.stderr)
        self.assertFalse((self.plugins / "varro-openjet").exists())


if __name__ == "__main__":
    unittest.main()
