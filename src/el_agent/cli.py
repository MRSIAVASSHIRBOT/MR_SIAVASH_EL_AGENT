"""CLI for setup, local server, and everyday agent commands."""
from __future__ import annotations
import argparse
import getpass
import json
import sys
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
import uvicorn
from el_agent.security import create_token, load_token, token_path
from el_agent.intent import parse_local_intent
from el_agent.hub import hub_status, pair_device, pairing_challenge_payload, reset_device_id, unpair_local

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
    commands.add_parser("device-id", help="Show the stable device ID and Hub pairing request")
    pair = commands.add_parser("pair", help="Pair this device with an owner-authorized Hub challenge")
    pair.add_argument("--hub-url", required=True, help="Hub origin; HTTPS required except localhost")
    commands.add_parser("hub-status", help="Show local Hub pairing and scope status")
    commands.add_parser("unpair", help="Remove local Hub credentials; owner must revoke remotely")
    commands.add_parser("reset-device-id", help="Create a new device ID after Hub-side revocation")
    commands.add_parser("gui", help="Open desktop control center and start local API if needed")
    serve = commands.add_parser("serve", help="Start local companion API")
    serve.add_argument("--host", choices=["127.0.0.1", "localhost"], default="127.0.0.1")
    serve.add_argument("--port", type=int, default=8765)
    commands.add_parser("status", help="Show local device capabilities")
    commands.add_parser("screenshot", help="Capture an explicit local screenshot")
    ask = commands.add_parser("ask", help="Run a fixed Persian/English local command")
    ask.add_argument("text", nargs="+", help="For example: اسکرین شات بگیر | برو داخل چت جی پی تی")
    launch = commands.add_parser("launch", help="Launch an allowlisted application")
    launch.add_argument("alias", choices=["notepad", "calculator", "explorer", "browser", "chatgpt"])
    schedule = commands.add_parser("schedule", help="Schedule an allowlisted app launch")
    schedule.add_argument("alias", choices=["notepad", "calculator", "explorer", "browser", "chatgpt"])
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
    if args.command == "device-id":
        print(json.dumps(pairing_challenge_payload(), ensure_ascii=False, indent=2))
        return
    if args.command == "pair":
        try:
            pairing_code = getpass.getpass("Paste the 5-minute Hub pairing code: ")
            result = pair_device(args.hub_url, pairing_code)
            print(json.dumps(result, ensure_ascii=False, indent=2))
        except (RuntimeError, ValueError) as exc:
            print(str(exc), file=sys.stderr)
            raise SystemExit(1) from exc
        return
    if args.command == "reset-device-id":
        confirmation = input("Only continue after revoking the old device in Hub. Type RESET to create a new identity: ")
        if confirmation != "RESET":
            raise SystemExit("Device identity reset cancelled.")
        try:
            print(json.dumps(reset_device_id(), ensure_ascii=False, indent=2))
        except RuntimeError as exc:
            print(str(exc), file=sys.stderr)
            raise SystemExit(1) from exc
        return
    if args.command == "hub-status":
        print(json.dumps(hub_status(), ensure_ascii=False, indent=2))
        return
    if args.command == "unpair":
        result = unpair_local()
        print(json.dumps(result, ensure_ascii=False, indent=2))
        if result["remote_revocation_required"]:
            print("Revoke this device in the Hub owner device registry as well.", file=sys.stderr)
        return
    if args.command == "serve":
        from el_agent.api import create_app
        uvicorn.run(create_app(), host=args.host, port=args.port, access_log=False, server_header=False)
        return
    if args.command == "gui":
        from el_agent.desktop import main as desktop_main
        desktop_main()
        return
    try:
        if args.command == "ask":
            intent = parse_local_intent(" ".join(args.text))
            if intent is None:
                raise ValueError("Unknown command. Only fixed, allowlisted Persian/English commands are supported.")
            if intent["action"] == "screenshot":
                result = api_call("POST", "/v1/screenshot")
            else:
                result = api_call("POST", f"/v1/apps/{intent['app_alias']}/launch")
        elif args.command == "status":
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
