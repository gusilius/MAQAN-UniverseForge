#!/usr/bin/env python3
"""Run one Java logic test with a hard timeout, so a stalled test cannot block CI."""
import subprocess
import sys
import time

if len(sys.argv) < 4:
    print("Usage: run-java-test.py TIMEOUT_SECONDS CLASSPATH MAIN_CLASS [ARGS...]", file=sys.stderr)
    sys.exit(2)

timeout_seconds = int(sys.argv[1])
classpath = sys.argv[2]
main_class = sys.argv[3]
args = sys.argv[4:]
command = ["java", "-Dstdout.encoding=UTF-8", "-cp", classpath, main_class, *args]
print(f"TEST_START {main_class} timeout={timeout_seconds}s", flush=True)
started = time.monotonic()
try:
    result = subprocess.run(command, timeout=timeout_seconds, check=False)
except subprocess.TimeoutExpired:
    elapsed = time.monotonic() - started
    print(f"TEST_TIMEOUT {main_class} after {elapsed:.1f}s", flush=True)
    sys.exit(124)
elapsed = time.monotonic() - started
print(f"TEST_END {main_class} exit={result.returncode} elapsed={elapsed:.1f}s", flush=True)
sys.exit(result.returncode)
