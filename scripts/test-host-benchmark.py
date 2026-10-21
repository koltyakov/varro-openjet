#!/usr/bin/env python3
"""Regression checks for the native bridge performance report gate."""

import contextlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("host_benchmark", Path(__file__).with_name("check-host-benchmark.py"))
benchmark = importlib.util.module_from_spec(spec)
spec.loader.exec_module(benchmark)


class BenchmarkGateTest(unittest.TestCase):
    def run_gate(self, reports, *args):
        with tempfile.TemporaryDirectory() as directory:
            for index, report in enumerate(reports):
                Path(directory, f"bridge-{index}.json").write_text(json.dumps(report))
            with patch.object(sys, "argv", ["check-host-benchmark.py", directory, *args]), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                return benchmark.main()

    def report(self, **changes):
        return dict(protocol="openjet-native-bridge-v1", acknowledged=128, batches=128,
                    startupMs=[100, 50], peakViews=4, roundTripMs={"count": 128, "p95": 10}) | changes

    def test_reports_must_include_acknowledged_work(self):
        for reports in ([], [self.report(acknowledged=0)]):
            with self.assertRaises(SystemExit):
                self.run_gate(reports)

    def test_view_count_and_budgets_are_enforced(self):
        self.assertEqual(0, self.run_gate([self.report()], "--min-views", "4", "--max-round-trip-p95-ms", "20", "--max-startup-ms", "200"))
        for args in (("--min-views", "5"), ("--max-round-trip-p95-ms", "5"), ("--max-startup-ms", "99")):
            self.assertEqual(1, self.run_gate([self.report()], *args))

    def test_worst_view_cannot_hide_behind_fast_views(self):
        slow = self.report(roundTripMs={"count": 128, "p95": 500})
        self.assertEqual(1, self.run_gate([self.report(), slow], "--max-round-trip-p95-ms", "100"))


if __name__ == "__main__":
    unittest.main()
