#!/usr/bin/env python3
"""
Android Log Monitor - ADB Logcat Wrapper

Monitor device logs with filtering.
"""

import argparse
import json
import sys
import subprocess
import threading
from common import ADB_PATH, resolve_serial, run_adb_command

def main():
    parser = argparse.ArgumentParser(description="Monitor Android Logs")
    parser.add_argument("--package", help="Filter by package name (requires app to be running)")
    parser.add_argument("--tag", help="Filter by tag")
    parser.add_argument("--priority", choices=["V", "D", "I", "W", "E", "F"], default="V", help="Minimum priority")
    parser.add_argument("--grep", help="Grep filter")
    parser.add_argument("--duration", type=float, help="Stop after this many seconds")
    parser.add_argument("--json", action="store_true", help="Emit matching log lines as JSON objects")
    parser.add_argument("--clear", "-c", action="store_true", help="Clear logs first")
    parser.add_argument("--serial", "-s", help="Device serial")

    args = parser.parse_args()

    try:
        serial = resolve_serial(args.serial)
    except RuntimeError as e:
        print(f"Error: {e}")
        sys.exit(1)

    if args.clear:
        run_adb_command(["logcat", "-c"], serial)
        print("Logs cleared.")

    cmd = ["logcat", "-v", "color", f"*:{args.priority}"]

    if args.tag:
        cmd = ["logcat", "-v", "color", "-s", args.tag]

    full_cmd = [ADB_PATH]
    if serial:
        full_cmd.extend(["-s", serial])
    full_cmd.extend(cmd)

    if args.package:
        # Get PID of package
        try:
            res = run_adb_command(["shell", "pidof", args.package], serial, check=False)
            pid = res.stdout.strip()
            if pid:
                print(f"Filtering for package {args.package} (PID: {pid})")
                full_cmd.append(f"--pid={pid}")
            else:
                print(f"Package {args.package} not running. Showing all logs.")
        except Exception:
            pass
            
    print(f"Running: {' '.join(full_cmd)}", file=sys.stderr if args.json else sys.stdout)
    try:
        with subprocess.Popen(
            full_cmd,
            stdout=subprocess.PIPE,
            stderr=sys.stderr,
            text=True,
        ) as process:
            timer = None
            if args.duration is not None:
                timer = threading.Timer(args.duration, process.terminate)
                timer.start()

            try:
                for line in process.stdout:
                    if args.grep and args.grep not in line:
                        continue

                    if args.json:
                        print(json.dumps({"line": line.rstrip("\n")}))
                    else:
                        print(line, end="")
            except KeyboardInterrupt:
                process.terminate()
                process.wait()
                return

            process.wait()
            if timer is not None:
                timer.cancel()
    except KeyboardInterrupt:
        sys.exit(0)

if __name__ == "__main__":
    main()
