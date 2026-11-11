#!/usr/bin/env python3
"""One-time Gmail consent for one JobRadar user (standard library only).

Opens Google's consent page, receives the authorization code on a loopback address and prints
the secret value to store. Nothing is written to disk and no token is logged.

Usage:
  export GOOGLE_CLIENT_ID=... GOOGLE_CLIENT_SECRET=...      # OAuth client of type "Desktop app"
  python3 scripts/gmail_authorize.py > /tmp/gmail-secret.json
  aws secretsmanager create-secret --name jobradar/gmail/<user id> --secret-string file:///tmp/gmail-secret.json
  shred -u /tmp/gmail-secret.json
  psql ... -c "UPDATE app_user SET gmail_token_ref = 'jobradar/gmail/<user id>' WHERE id = <user id>"

The only scope requested is gmail.send, the narrowest one that lets the app send an application.
"""
import http.server
import json
import os
import secrets
import sys
import urllib.parse
import urllib.request
import webbrowser

SCOPE = "https://www.googleapis.com/auth/gmail.send"
AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
TOKEN_URL = "https://oauth2.googleapis.com/token"


def main() -> int:
    client_id = os.environ.get("GOOGLE_CLIENT_ID")
    client_secret = os.environ.get("GOOGLE_CLIENT_SECRET")
    if not client_id or not client_secret:
        print("Set GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET.", file=sys.stderr)
        return 2

    state = secrets.token_urlsafe(16)
    received = {}

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):  # noqa: N802 (name fixed by the base class)
            query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            received.update({key: values[0] for key, values in query.items()})
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(b"Done. You can close this tab.")

        def log_message(self, *args):  # keep the code out of any log output
            pass

    server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
    redirect = f"http://127.0.0.1:{server.server_port}"
    url = AUTH_URL + "?" + urllib.parse.urlencode({
        "client_id": client_id,
        "redirect_uri": redirect,
        "response_type": "code",
        "scope": SCOPE,
        "access_type": "offline",  # needed to receive a refresh token
        "prompt": "consent",
        "state": state,
    })
    print("Open this URL if the browser does not start:\n" + url, file=sys.stderr)
    webbrowser.open(url)
    server.handle_request()

    if received.get("state") != state or "code" not in received:
        print("Consent failed or the state did not match.", file=sys.stderr)
        return 1
    body = urllib.parse.urlencode({
        "code": received["code"],
        "client_id": client_id,
        "client_secret": client_secret,
        "redirect_uri": redirect,
        "grant_type": "authorization_code",
    }).encode()
    with urllib.request.urlopen(urllib.request.Request(TOKEN_URL, data=body)) as response:
        refresh = json.load(response).get("refresh_token")
    if not refresh:
        print("Google returned no refresh token. Remove the app from your Google account and retry.", file=sys.stderr)
        return 1
    print(json.dumps({"refresh_token": refresh}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
