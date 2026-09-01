from __future__ import annotations

import json
import subprocess
from pathlib import Path
from urllib.parse import urlencode

BASE_DIR = Path(__file__).resolve().parent
REQUEST_FILE = BASE_DIR / "remote_request.json"
ACTION = "com.wanttalk.phonerunner.SET_SCHEDULE"
RECEIVER = "com.wanttalk.phonerunner/.ScheduleCommandReceiver"
MINUTES_MIN = 1
MINUTES_MAX = 10080


def main() -> int:
    try:
        request = json.loads(REQUEST_FILE.read_text(encoding="utf-8"))
        minutes = int(request.get("interval_minutes"))
        request_id = str(request.get("request_id", "")).strip()[:220]
    except (OSError, TypeError, ValueError, json.JSONDecodeError) as exc:
        print(f"INVALID_SCHEDULE_REQUEST: {type(exc).__name__}: {exc}")
        return 2

    if not request_id:
        print("INVALID_SCHEDULE_REQUEST: missing request_id")
        return 2
    if minutes < MINUTES_MIN or minutes > MINUTES_MAX:
        print(f"INVALID_SCHEDULE_REQUEST: interval_minutes must be {MINUTES_MIN}..{MINUTES_MAX}")
        return 2

    command = [
        "/system/bin/am",
        "broadcast",
        "--user",
        "current",
        "-n",
        RECEIVER,
        "-a",
        ACTION,
        "--el",
        "interval_minutes",
        str(minutes),
        "--es",
        "request_id",
        request_id,
    ]
    try:
        process = subprocess.run(
            command,
            cwd=BASE_DIR,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=30,
        )
    except Exception as exc:
        print(f"SCHEDULE_BROADCAST_FAILED: {type(exc).__name__}: {exc}")
        return 70

    output = process.stdout or ""
    print(output.rstrip())
    if process.returncode != 0:
        return process.returncode
    if "result=1" in output:
        return 1
    print(f"SCHEDULE_SET interval_minutes={minutes}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
