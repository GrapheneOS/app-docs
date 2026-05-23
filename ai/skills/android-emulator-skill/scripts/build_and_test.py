#!/usr/bin/env python3
"""
Android Build & Test - Gradle Wrapper

Builds projects and runs tests with parsed output.
"""

import argparse
import json
import sys
import subprocess
import os

def find_gradlew():
    """Find gradlew in current or parent directories."""
    cwd = os.getcwd()
    while cwd != "/":
        path = os.path.join(cwd, "gradlew")
        if os.path.exists(path):
            return path
        cwd = os.path.dirname(cwd)
    return None

def run_gradle_task(task, clean=False, verbose=False, json_output=False):
    gradlew = find_gradlew()
    if not gradlew:
        return {
            "success": False,
            "task": task,
            "error": "gradlew not found in current directory tree",
        }

    cmd = [gradlew, task]
    if clean:
        cmd.insert(1, "clean")
    
    if not verbose:
        cmd.append("-q") # Quiet mode

    if not json_output:
        print(f"Running: {' '.join(cmd)}")
    
    try:
        process = subprocess.Popen(
            cmd, 
            stdout=subprocess.PIPE, 
            stderr=subprocess.STDOUT,
            text=True
        )
        
        # Stream output
        output_lines = []
        for line in process.stdout:
            output_lines.append(line)
            if verbose:
                print(line, end="")
        
        process.wait()

        result = {
            "success": process.returncode == 0,
            "task": task,
            "command": cmd,
            "return_code": process.returncode,
        }
        
        if process.returncode == 0:
            if not json_output:
                print(f"Build successful: {task}")
            return result

        result["last_output_lines"] = output_lines[-20:]
        if not json_output:
            print(f"Build failed: {task}")
            if not verbose:
                print("Error details (last 20 lines):")
                print("".join(output_lines[-20:]))
        return result

    except Exception as e:
        return {
            "success": False,
            "task": task,
            "command": cmd,
            "error": str(e),
        }

def main():
    parser = argparse.ArgumentParser(description="Build and Test Android Project")
    parser.add_argument("--task", default="assembleDebug", help="Gradle task to run")
    parser.add_argument("--test", action="store_true", help="Run connectedAndroidTest")
    parser.add_argument("--clean", action="store_true", help="Run clean before task")
    parser.add_argument("--verbose", action="store_true", help="Show full gradle output")
    parser.add_argument("--json", action="store_true", help="Output JSON")

    args = parser.parse_args()

    task = args.task
    if args.test:
        task = "connectedAndroidTest"

    result = run_gradle_task(
        task=task,
        clean=args.clean,
        verbose=args.verbose,
        json_output=args.json,
    )

    if args.json:
        print(json.dumps(result, indent=2))

    if result["success"]:
        sys.exit(0)
    else:
        if not args.json and "error" in result:
            print(f"Error running gradle: {result['error']}")
        sys.exit(1)

if __name__ == "__main__":
    main()
