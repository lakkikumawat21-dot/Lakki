#!/usr/bin/env python3
import argparse
import json
import sys
import urllib.error
import urllib.request
from urllib.parse import quote

ACCESS_PORT = __import__("os").environ.get("HERMES_ANDROID_CAPABILITY_PORT", "")
BASE_URL = f"http://127.0.0.1:{ACCESS_PORT}" if ACCESS_PORT else ""
ACCESS_TOKEN = __import__("os").environ.get("HERMES_ANDROID_CAPABILITY_TOKEN", "")


def request(path, payload=None, timeout=5):
    if not BASE_URL or not ACCESS_TOKEN:
        raise RuntimeError("Android capability credentials are unavailable")
    data = None
    headers = {}
    method = "GET"
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
        method = "POST"
    req = urllib.request.Request(f"{BASE_URL}{path}", data=data, headers=headers, method=method)
    if ACCESS_TOKEN:
        req.add_header("Authorization", f"Bearer {ACCESS_TOKEN}")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:
            body = res.read().decode("utf-8")
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as err:
        body = err.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"Device capability request failed: HTTP {err.code} {body}") from err
    except urllib.error.URLError as err:
        raise RuntimeError(f"Device capability server is unavailable: {err}") from err


def main():
    parser = argparse.ArgumentParser(description="Call local device capabilities")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("health")
    sub.add_parser("tools")
    sub.add_parser("device-info")

    notify = sub.add_parser("notify")
    notify.add_argument("--title", required=True)
    notify.add_argument("--message", required=True)

    alarm = sub.add_parser("alarm")
    alarm.add_argument("--delay-seconds", type=int, default=60)
    alarm.add_argument("--title", default="Hermes Agent reminder")
    alarm.add_argument("--message", default="Scheduled by Hermes Agent")

    toast = sub.add_parser("toast")
    toast.add_argument("--message", required=True)

    clipboard_set = sub.add_parser("clipboard-set")
    clipboard_set.add_argument("--text", required=True)

    sub.add_parser("clipboard-get")

    open_url = sub.add_parser("open-url")
    open_url.add_argument("--url", required=True)

    file_list = sub.add_parser("file-list")
    file_list.add_argument("--path", default="")
    file_list.add_argument("--recursive", action="store_true")

    file_share = sub.add_parser("file-share")
    file_share.add_argument("--path", required=True)

    file_save = sub.add_parser("file-save")
    file_save.add_argument("--path", required=True)
    file_save.add_argument("--destination", choices=["downloads", "pick"], default="downloads")
    file_save.add_argument("--name", default=None)

    file_pick = sub.add_parser("file-pick")
    file_pick.add_argument("--target-dir", default="")
    file_pick.add_argument("--mime", default="*/*")

    file_capture = sub.add_parser("file-capture")
    file_capture.add_argument("--target-dir", default="")
    file_capture.add_argument("--name", default=None)

    file_events = sub.add_parser("file-events")
    file_events.add_argument("--since", type=int, default=None)
    file_events.add_argument("--type", default=None)
    file_events.add_argument("--limit", type=int, default=None)

    sub.add_parser("file-diagnostics")
    sub.add_parser("file-backup")

    args = parser.parse_args()
    if args.command == "health":
        result = request("/health")
    elif args.command == "tools":
        result = request("/tools")
    elif args.command == "device-info":
        result = request("/device-info")
    elif args.command == "notify":
        result = request("/notify", {"title": args.title, "message": args.message})
    elif args.command == "alarm":
        result = request(
            "/alarm",
            {
                "delay_seconds": str(max(args.delay_seconds, 1)),
                "title": args.title,
                "message": args.message,
            },
        )
    elif args.command == "toast":
        result = request("/toast", {"message": args.message})
    elif args.command == "clipboard-set":
        result = request("/clipboard", {"text": args.text})
    elif args.command == "clipboard-get":
        result = request("/clipboard")
    elif args.command == "open-url":
        result = request("/open-url", {"url": args.url})
    elif args.command == "file-list":
        suffix = "&recursive=1" if args.recursive else ""
        result = request(f"/file/list?path={quote(args.path)}{suffix}")
    elif args.command == "file-share":
        result = request("/file/share", {"path": args.path})
    elif args.command == "file-save":
        payload = {"path": args.path, "destination": args.destination}
        if args.name:
            payload["name"] = args.name
        result = request("/file/save", payload, timeout=200 if args.destination == "pick" else 30)
    elif args.command == "file-pick":
        result = request(
            "/file/pick",
            {"target_dir": args.target_dir, "mime": args.mime},
            timeout=200,
        )
    elif args.command == "file-capture":
        payload = {"target_dir": args.target_dir}
        if args.name:
            payload["name"] = args.name
        result = request("/file/capture", payload, timeout=200)
    elif args.command == "file-events":
        params = []
        if args.since:
            params.append(f"since={args.since}")
        if args.type:
            params.append(f"type={quote(args.type)}")
        if args.limit:
            params.append(f"limit={args.limit}")
        suffix = ("?" + "&".join(params)) if params else ""
        result = request(f"/file/events{suffix}")
    elif args.command == "file-diagnostics":
        result = request("/diagnostics")
    elif args.command == "file-backup":
        result = request("/workspace/backup", {}, timeout=300)
    else:
        parser.error("unknown command")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(1)
