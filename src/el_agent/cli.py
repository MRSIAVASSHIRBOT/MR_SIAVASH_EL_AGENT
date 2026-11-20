"""CLI for setup, local server, and everyday agent commands."""
from __future__ import annotations
import argparse
import json
import sys
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
import uvicorn
from el_agent.security import create_token, load_token, token_path

API = "http://127.0.0.1:8765"

def api_call(method: str, path: str, payload: dict | None = None):
    body = json.dumps(payload).encode("utf-8") if payload is not None else None
    request = Request(API + path, data=body, method=method, headers={
        "Authorization": "Bearer " + load_token(),
        "Content-Type": "application/json",
    })
    try:
        with urlopen(request, timeout=15) as response:
            raw = response.read()
            return json.loads(raw.decode("utf-8")) if raw else {}
    except HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"Agent API returned HTTP {exc.code}: {detail}") from exc
    except URLError as exc:
        raise RuntimeError("Agent is not running. Start it in another terminal with: el-agent serve") from exc

def main() -> None:
    parser = argparse.ArgumentParser(prog="el-agent", description="MR_SIAVASH_EL_AGENT local Windows companion")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("setup", help="Create local API bearer token")
    serve = commands.add_parser("serve", help="Start local companion API")
    serve.add_argument("--host", choices=["127.0.0.1", "localhost"], default="127.0.0.1")
    serve.add_argument("--port", type=int, default=8765)
    commands.add_parser("status", help="Show local device capabilities")
    commands.add_parser("screenshot", help="Capture an explicit local screenshot")
    launch = commands.add_parser("launch", help="Launch an allowlisted application")
    launch.add_argument("alias", choices=["notepad", "calculator", "explorer", "browser"])
    schedule = commands.add_parser("schedule", help="Schedule an allowlisted app launch")
    schedule.add_argument("alias", choices=["notepad", "calculator", "explorer", "browser"])
    schedule.add_argument("--at", required=True, help="Timezone-aware ISO 8601 date/time")
    commands.add_parser("tasks", help="List scheduled tasks")
    cancel = commands.add_parser("cancel", help="Cancel a pending scheduled task")
    cancel.add_argument("task_id")
    args = parser.parse_args()

    if args.command == "setup":
        try:
            token = create_token()
        except FileExistsError:
            print(f"Token already exists at {token_path()}; it was not replaced.", file=sys.stderr)
            raise SystemExit(2)
        print(f"Token file: {token_path()}\nCopy this token now; it will not be printed again:\n{token}\nKeep it private. Server is loopback-only.")
        return
    if args.command == "serve":
        from el_agent.api import create_app
        uvicorn.run(create_app(), host=args.host, port=args.port, access_log=False, server_header=False)
        return
    try:
        if args.command == "status":
            result = api_call("GET", "/v1/device")
        elif args.command == "screenshot":
            result = api_call("POST", "/v1/screenshot")
        elif args.command == "launch":
            result = api_call("POST", f"/v1/apps/{args.alias}/launch")
        elif args.command == "schedule":
            result = api_call("POST", "/v1/tasks", {"action": "launch_app", "app_alias": args.alias, "run_at": args.at})
        elif args.command == "tasks":
            result = api_call("GET", "/v1/tasks")
        else:
            result = api_call("DELETE", f"/v1/tasks/{args.task_id}")
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (RuntimeError, ValueError) as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(1) from exc

if __name__ == "__main__":
    main()
