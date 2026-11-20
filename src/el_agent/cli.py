"""CLI for setup and local server startup."""
from __future__ import annotations
import argparse
import sys
import uvicorn
from el_agent.security import create_token, token_path

def main() -> None:
    parser = argparse.ArgumentParser(prog="el-agent")
    commands = parser.add_subparsers(dest="command",required=True)
    commands.add_parser("setup",help="Create local API bearer token")
    serve = commands.add_parser("serve",help="Start local companion API")
    serve.add_argument("--host",choices=["127.0.0.1","localhost"],default="127.0.0.1")
    serve.add_argument("--port",type=int,default=8765)
    args = parser.parse_args()
    if args.command == "setup":
        try: token = create_token()
        except FileExistsError:
            print(f"Token already exists at {token_path()}; it was not replaced.",file=sys.stderr)
            raise SystemExit(2)
        print(f"Token file: {token_path()}\nCopy this token now; it will not be printed again:\n{token}\nKeep it private. Server is loopback-only.")
        return
    from el_agent.api import create_app
    uvicorn.run(create_app(),host=args.host,port=args.port,access_log=False,server_header=False)

if __name__ == "__main__":
    main()
