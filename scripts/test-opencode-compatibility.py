#!/usr/bin/env python3
"""Regression checks for release selection and skipped-test rejection. No live CLI is started."""

import argparse
import importlib.util
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("compatibility", Path(__file__).with_name("check-opencode-compatibility.py"))
compatibility = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compatibility)


class CompatibilityTest(unittest.TestCase):
    def test_full_ci_runs_all_tests_static_checks_and_build_in_one_invocation(self):
        command = compatibility.gradle_command(full_check=True)
        self.assertEqual(command[1:], ["--no-daemon", "--stacktrace", "check", "buildPlugin", "--rerun-tasks"])
        self.assertNotIn("--tests", command)
        self.assertNotIn("-PskipWebview=true", command)

    def test_focused_compatibility_keeps_both_released_suites(self):
        command = compatibility.gradle_command()
        self.assertEqual(command[1:4], ["test", "-PskipWebview=true", "--rerun-tasks"])
        self.assertEqual(command[4:], [item for suite in compatibility.SUITES for item in ("--tests", suite)])

    def test_only_exact_v2_releases_are_accepted(self):
        self.assertEqual("2.0.28", compatibility.released_version("2.0.28"))
        for version in ("latest", "^2.0.28", "1.19.0", "2.0.28-preview", "../2.0.28"):
            with self.assertRaises(argparse.ArgumentTypeError):
                compatibility.released_version(version)

    def test_binary_version_is_parsed_without_accepting_other_output(self):
        for value in ("opencode v2.0.28\n", "2.0.28"):
            self.assertEqual("2.0.28", compatibility.cli_version(value))
        for value in ("error 2.0.28", "opencode v1.19.0", "2.0.28\nservice started"):
            with self.assertRaises(ValueError):
                compatibility.cli_version(value)

    def reports(self, directory, **counts):
        attributes = {"tests": 1, "failures": 0, "errors": 0, "skipped": 0, **counts}
        for name in compatibility.SUITES:
            fields = " ".join(f'{key}="{value}"' for key, value in attributes.items())
            (directory / f"TEST-{name}.xml").write_text(f'<testsuite name="{name}" {fields}/>')

    def test_both_suites_must_pass_without_skips(self):
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            self.reports(directory)
            self.assertEqual(2, len(compatibility.read_results(directory)))
            for counts in ({"tests": 0}, {"skipped": 1}, {"failures": 1}, {"errors": 1}):
                self.reports(directory, **counts)
                with self.assertRaises(ValueError):
                    compatibility.read_results(directory)
            self.reports(directory)
            (directory / f"TEST-{compatibility.SUITES[0]}.xml").unlink()
            with self.assertRaises(FileNotFoundError):
                compatibility.read_results(directory)

    def test_version_probes_use_private_state_and_database_paths(self):
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            environment = compatibility.probe_environment(directory)
            for key in ("HOME", "USERPROFILE", "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_STATE_HOME", "XDG_CACHE_HOME", "OPENCODE_DB", "VARRO_TEST_STATE_ROOT"):
                self.assertTrue(Path(environment[key]).is_relative_to(directory))
            self.assertNotIn("OPENCODE_SERVER_PASSWORD", environment)


if __name__ == "__main__":
    unittest.main()
