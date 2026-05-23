#!/usr/bin/env python3
"""
Android Emulator Manager - AVD Lifecycle

List, boot, and shutdown Android Virtual Devices.
"""

import argparse
import json
import sys
import subprocess
import os
from common import resolve_serial, run_adb_command

def get_emulator_path():
    """Get path to emulator executable."""
    android_home = os.environ.get("ANDROID_HOME")
    if android_home:
        path = os.path.join(android_home, "emulator", "emulator")
        if os.path.exists(path):
            return path
    
    # Check if in PATH
    try:
        subprocess.run(["emulator", "-version"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return "emulator"
    except Exception:
        pass
        
    return "emulator"

def list_avds():
    """List available AVDs."""
    emu = get_emulator_path()
    try:
        res = subprocess.run([emu, "-list-avds"], capture_output=True, text=True, check=True)
        avds = [line.strip() for line in res.stdout.splitlines() if line.strip()]
        return avds
    except RuntimeError:
        return []

def boot_avd(avd_name, verbose=True):
    """Boot an AVD."""
    emu = get_emulator_path()
    if verbose:
        print(f"Booting {avd_name}...")
    # Launch in background
    try:
        subprocess.Popen([emu, "-avd", avd_name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if verbose:
            print(f"Emulator {avd_name} started.")
        return True
    except Exception as e:
        if verbose:
            print(f"Failed to boot: {e}")
        return False

def shutdown_emulator(serial, verbose=True):
    """Shutdown an emulator instance."""
    try:
        run_adb_command(["emu", "kill"], serial)
        if verbose:
            print(f"Shutdown signal sent to {serial}")
        return True
    except subprocess.CalledProcessError:
        if verbose:
            print(f"Failed to shutdown {serial}")
        return False

def main():
    parser = argparse.ArgumentParser(description="Manage Android Emulators")
    parser.add_argument("--list", action="store_true", help="List available AVDs")
    parser.add_argument("--boot", help="Boot AVD by name")
    parser.add_argument("--shutdown", help="Shutdown emulator by serial")
    parser.add_argument("--json", action="store_true", help="Output JSON")
    
    args = parser.parse_args()

    if args.list:
        avds = list_avds()
        if args.json:
            print(json.dumps({"success": True, "avds": avds}, indent=2))
        else:
            print("Available AVDs:")
            for avd in avds:
                print(f"  - {avd}")
            
    elif args.boot:
        success = boot_avd(args.boot, verbose=not args.json)
        if args.json:
            print(json.dumps({"success": success, "avd": args.boot}, indent=2))
        
    elif args.shutdown:
        # If shutdown arg is provided, treat it as serial if likely
        serial = args.shutdown
        success = shutdown_emulator(serial, verbose=not args.json)
        if args.json:
            print(json.dumps({"success": success, "serial": serial}, indent=2))
        
    else:
        parser.print_help()

if __name__ == "__main__":
    main()
