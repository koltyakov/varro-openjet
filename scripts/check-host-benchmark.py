#!/usr/bin/env python3
"""Summarize opt-in production JCEF bridge samples and optionally enforce local budgets."""

import argparse
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--min-views", type=int, default=1)
    parser.add_argument("--max-round-trip-p95-ms", type=float)
    parser.add_argument("--max-startup-ms", type=float)
    args = parser.parse_args()
    reports = [json.loads(path.read_text()) for path in sorted(args.directory.glob("bridge-*.json"))]
    reports = [report for report in reports if report.get("protocol") == "openjet-native-bridge-v1"]
    if not reports or not any(report["acknowledged"] for report in reports):
        parser.error("No acknowledged native bridge samples. Open a chat, exercise it, then close the view.")
    peak = max(report["peakViews"] for report in reports)
    startup = [value for report in reports for value in report["startupMs"]]
    p95 = max((report["roundTripMs"]["p95"] for report in reports if report["roundTripMs"]["count"]), default=0)
    summary = {"reports": len(reports), "peakViews": peak, "startupMs": startup,
               "worstViewRoundTripP95Ms": p95, "batches": sum(report["batches"] for report in reports),
               "acknowledged": sum(report["acknowledged"] for report in reports)}
    print(json.dumps(summary, indent=2))
    failed = peak < args.min_views or not startup
    if args.max_round_trip_p95_ms is not None:
        failed |= p95 > args.max_round_trip_p95_ms
    if args.max_startup_ms is not None:
        failed |= any(value > args.max_startup_ms for value in startup)
    return int(failed)


if __name__ == "__main__":
    raise SystemExit(main())
