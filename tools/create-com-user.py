#!/usr/bin/env python3
"""Create a Morpheus role (and optionally a user) for the HPE COM tabs of
com-morpheus-plugin, in one run instead of hundreds of clicks.

Usage:  python3 create-com-user.py <morpheus-ip-or-hostname>

Everything else is prompted for. The script uses only the Python standard
library, writes no files, keeps no logs, and never echoes passwords. When it
finishes it deletes the admin token it created for its own login, so the only
things left behind are the new role and, if you asked for one, the new user.

What the role gets, and nothing else:
- "read" on the pages the HPE COM tabs live on:
    Operations: Dashboard, Infrastructure: Compute, Clusters, Groups, Clouds
- read access to all groups and clouds (default access), so those pages list
  the hosts and clusters.
- the plugin's own permissions:
    com-morpheus: View HPE COM tab     = Read
    com-morpheus: Run HPE COM actions  = Full   (only if you choose "view and act")
Every other permission stays at "none". The script reads the role back and
stops before creating a user if anything else is above none.

Adapted from create-readonly-user.py in dscc-morpheus-plugin (same author, MIT).
"""
from __future__ import annotations

import argparse
import getpass
import hashlib
import http.client
import json
import socket
import ssl
import sys
import urllib.parse

CLIENT_ID = "morph-api"
VIEW_PERMISSION = "com-morpheus-view"
ACTIONS_PERMISSION = "com-morpheus-actions"

# The only feature permissions granted (read). Codes checked on Morpheus 9.0.1 and 9.1.0.
ALLOW_READ = {
    "dashboard": "Operations: Dashboard",
    "admin-servers": "Infrastructure: Compute",
    "infrastructure-cluster": "Infrastructure: Clusters",
    "admin-groups": "Infrastructure: Groups",
    "admin-zones": "Infrastructure: Clouds",
}

# Default (global) access, set to "read": groups (ComputeSite) and clouds (ComputeZone).
GLOBAL_ACCESS = ("ComputeSite", "ComputeZone")

READ_LEVELS = ("read", "view")
NONE_LEVELS = {"none", "no", None}
GRANTED_LEVELS = {"read", "view"}


class ApiError(RuntimeError):
    def __init__(self, status: int, body: str):
        super().__init__(f"HTTP {status}: {body[:300]}")
        self.status, self.body = status, body


# -- TLS: system trust, or trust-on-first-use with the fingerprint shown ------
class Server:
    def __init__(self, host: str, port: int = 443):
        self.host, self.port = host, port
        self.pinned: str | None = None
        self.ctx = ssl.create_default_context()
        try:
            with socket.create_connection((host, port), timeout=15) as s, \
                    self.ctx.wrap_socket(s, server_hostname=host):
                pass
            print(f"TLS certificate for {host} is trusted by this system.")
        except ssl.SSLCertVerificationError:
            pem = ssl.get_server_certificate((host, port), timeout=15)
            der = ssl.PEM_cert_to_DER_cert(pem)
            fp = hashlib.sha256(der).hexdigest().upper()
            pretty = ":".join(fp[i:i + 2] for i in range(0, len(fp), 2))
            print(f"\n{host} uses a certificate this system does not trust "
                  f"(usually self-signed).\nSHA-256 fingerprint:\n  {pretty}")
            print("Compare it with the appliance's certificate before continuing.")
            if not confirm("Trust this certificate for this run only?"):
                sys.exit("Stopped: certificate not trusted.")
            self.pinned = fp
            self.ctx = ssl.create_default_context()
            self.ctx.check_hostname = False
            self.ctx.verify_mode = ssl.CERT_NONE  # replaced by the fingerprint check below

    def request(self, method: str, path: str, token: str | None = None,
                body: dict | None = None, form: dict | None = None) -> dict:
        conn = http.client.HTTPSConnection(self.host, self.port, context=self.ctx, timeout=60)
        try:
            conn.connect()
            if self.pinned:
                der = conn.sock.getpeercert(binary_form=True)
                if hashlib.sha256(der).hexdigest().upper() != self.pinned:
                    raise SystemExit("Stopped: the server certificate changed during the run.")
            headers = {"Accept": "application/json"}
            data = None
            if token:
                headers["Authorization"] = f"Bearer {token}"
            if form is not None:
                data = urllib.parse.urlencode(form).encode()
                headers["Content-Type"] = "application/x-www-form-urlencoded"
            elif body is not None:
                data = json.dumps(body).encode()
                headers["Content-Type"] = "application/json"
            conn.request(method, path, body=data, headers=headers)
            resp = conn.getresponse()
            text = resp.read().decode("utf-8", "replace")
            if resp.status >= 400:
                raise ApiError(resp.status, text)
            return json.loads(text) if text.strip() else {}
        finally:
            conn.close()

    def login(self, username: str, password: str) -> dict:
        return self.request("POST", "/oauth/token", form={
            "grant_type": "password", "scope": "write", "client_id": CLIENT_ID,
            "username": username, "password": password})

    def delete_own_token(self, token: str) -> bool:
        """Delete exactly this token (not the user's other tokens)."""
        try:
            found = self.request("POST", "/api/tokens/lookup", token=token, body={"token": token})
            tid = (found.get("token") or {}).get("id")
            if tid:
                self.request("DELETE", f"/api/tokens/{tid}", token=token)
                return True
        except (ApiError, OSError):
            pass
        return False


# -- prompts -------------------------------------------------------------------
def confirm(question: str, default: bool = False) -> bool:
    hint = "[Y/n]" if default else "[y/N]"
    answer = input(f"{question} {hint} ").strip().lower()
    return default if not answer else answer in ("y", "yes")


def ask(question: str, default: str | None = None) -> str:
    while True:
        suffix = f" [{default}]" if default else ""
        answer = input(f"{question}{suffix}: ").strip()
        if answer or default:
            return answer or default
        print("  A value is required.")


def ask_password(question: str) -> str:
    while True:
        first = getpass.getpass(f"{question}: ")
        if not first:
            print("  A password is required.")
            continue
        if getpass.getpass("Repeat the password: ") == first:
            return first
        print("  The passwords do not match. Try again.")


def message(err: ApiError) -> str:
    try:
        data = json.loads(err.body)
        parts = [data.get("msg") or data.get("error_description") or data.get("error") or ""]
        errors = data.get("errors") or {}
        parts += [f"{k}: {v}" for k, v in errors.items()] if isinstance(errors, dict) else []
        return "; ".join(p for p in parts if p) or err.body[:200]
    except ValueError:
        return err.body[:200]


# -- steps ---------------------------------------------------------------------
def choose_access() -> bool:
    print("\nWhat should people with this role be able to do?")
    print("  1  View the HPE COM tabs (read-only)")
    print("  2  View the tabs and run the HPE COM actions (UID light, Collect inventory,")
    print("     iLO reset, COM maintenance). They act in COM as the plugin's GreenLake")
    print("     API client, so grant this only to people you'd trust with that account.")
    while True:
        answer = ask("Choose 1 or 2", "1")
        if answer in ("1", "2"):
            return answer == "2"
        print("  Type 1 or 2.")


def create_role(srv: Server, admin: str, actions: bool) -> tuple[int, str]:
    default = "hpe-com-operator" if actions else "hpe-com-viewer"
    while True:
        name = ask("Name for the new role", default)
        try:
            res = srv.request("POST", "/api/roles", token=admin, body={"role": {
                "authority": name, "roleType": "user",
                "description": ("Host and cluster pages plus HPE COM tab and actions" if actions else
                                "Host and cluster pages plus HPE COM tab") + " (created by create-com-user.py)"}})
            return res["role"]["id"], name
        except ApiError as e:
            print(f"  Could not create role '{name}': {message(e)}")
            if not confirm("Try a different name?", True):
                sys.exit("Stopped: no role created.")


def plugin_permissions_present(srv: Server, admin: str, role_id: int) -> bool:
    role = srv.request("GET", f"/api/roles/{role_id}?includeDefaultAccess=true", token=admin)
    codes = {p.get("code") for p in role.get("featurePermissions", [])}
    return VIEW_PERMISSION in codes and ACTIONS_PERMISSION in codes


def grant(srv: Server, admin: str, role_id: int, actions: bool) -> dict:
    role = srv.request("GET", f"/api/roles/{role_id}?includeDefaultAccess=true", token=admin)
    path = f"/api/roles/{role_id}/update-permission"
    present = {p.get("code") for p in role.get("featurePermissions", [])}
    counts = {"read": [], "missing": [], "global": 0}
    for code, label in ALLOW_READ.items():
        if code not in present:
            counts["missing"].append(label)
            continue
        for level in READ_LEVELS:
            try:
                srv.request("PUT", path, token=admin, body={"permissionCode": code, "access": level})
                counts["read"].append(label)
                break
            except ApiError as e:
                if e.status not in (400, 422):
                    raise
        else:
            counts["missing"].append(label)
    for code in GLOBAL_ACCESS:
        try:
            srv.request("PUT", path, token=admin, body={"permissionCode": code, "access": "read"})
            counts["global"] += 1
        except ApiError as e:
            if e.status not in (400, 422):
                raise
    srv.request("PUT", path, token=admin, body={"permissionCode": VIEW_PERMISSION, "access": "read"})
    srv.request("PUT", path, token=admin, body={
        "permissionCode": ACTIONS_PERMISSION, "access": "full" if actions else "none"})
    counts["problems"] = verify_role(srv, admin, role_id, actions)
    return counts


def verify_role(srv: Server, admin: str, role_id: int, actions: bool) -> list[str]:
    """Read the role back. Only the allowlisted permissions may be read, the view
    permission must be read, the actions permission full only when asked for, and
    everything else none."""
    role = srv.request("GET", f"/api/roles/{role_id}?includeDefaultAccess=true", token=admin)
    bad = []
    levels = {p.get("code"): p.get("access") for p in role.get("featurePermissions", [])}
    for code, access in levels.items():
        if code == ACTIONS_PERMISSION:
            if access != ("full" if actions else "none"):
                bad.append(f"{code}={access} (expected {'full' if actions else 'none'})")
        elif code == VIEW_PERMISSION:
            if access != "read":
                bad.append(f"{code}={access} (expected read)")
        elif code in ALLOW_READ:
            if access not in GRANTED_LEVELS | NONE_LEVELS:
                bad.append(f"{code}={access} (expected read)")
        elif access not in NONE_LEVELS:
            bad.append(f"{code}={access} (expected none)")
    for key, value in role.items():
        if key.startswith("global") and key.endswith("Access") and value not in GRANTED_LEVELS | NONE_LEVELS | {"custom"}:
            bad.append(f"{key}={value}")
    return bad


def create_user(srv: Server, admin: str, role_id: int, role_name: str) -> str:
    print(f"\nNow the user who gets the '{role_name}' role.")
    while True:
        username = ask("Username")
        email = ask("Email for the user (required by Morpheus)", f"{username}@example.invalid")
        first = ask("First name", "HPE COM")
        last = ask("Last name", username)
        password = ask_password(f"Password for {username}")
        try:
            srv.request("POST", "/api/users", token=admin, body={"user": {
                "username": username, "email": email, "firstName": first,
                "lastName": last, "password": password,
                "passwordConfirmation": password, "roles": [{"id": role_id}],
                "receiveNotifications": False}})
            return username
        except ApiError as e:
            print(f"  Could not create user: {message(e)}")
            if not confirm("Try again?", True):
                print("  No user created. You can give the role to existing users instead.")
                return ""
        finally:
            password = None


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("server", help="Morpheus appliance IP address or hostname")
    p.add_argument("--port", type=int, default=443)
    args = p.parse_args()

    srv = Server(args.server, args.port)
    print("\nLog in with an administrator account (used only for this run).")
    admin_user = ask("Admin username")
    try:
        admin = srv.login(admin_user, getpass.getpass("Admin password: "))["access_token"]
    except ApiError as e:
        sys.exit(f"Admin login failed: {message(e)}")

    username = ""
    try:
        actions = choose_access()
        role_id, role_name = create_role(srv, admin, actions)
        if not plugin_permissions_present(srv, admin, role_id):
            srv.request("DELETE", f"/api/roles/{role_id}", token=admin)
            sys.exit("Stopped: this appliance has no com-morpheus permissions, so the plugin isn't\n"
                     "installed (or is disabled). Install it first: Administration > Integrations >\n"
                     "Plugins. The empty role was removed.")
        print(f"Created role '{role_name}' (id {role_id}). Setting permissions ...")
        counts = grant(srv, admin, role_id, actions)
        print("  Read: " + ", ".join(counts["read"]))
        print(f"  Default access read on {counts['global']} types (groups, clouds)")
        if counts["missing"]:
            print("  Not offered on this appliance, left as they are: " + ", ".join(counts["missing"]))
        print("  HPE COM: View HPE COM tab = Read; Run HPE COM actions = " + ("Full" if actions else "None"))
        if counts["problems"]:
            print("\nThe role isn't as expected, so no user was created:")
            for item in counts["problems"]:
                print(f"  - {item}")
            sys.exit(f"Review role '{role_name}' in Administration > Roles, then run again.")
        print("  Checked: every other permission on the role is none.")
        if confirm("\nCreate a new user with this role now?", True):
            username = create_user(srv, admin, role_id, role_name)
            if username:
                print(f"Created user '{username}'.")
    finally:
        removed = srv.delete_own_token(admin)
        print("Removed this run's admin token." if removed else
              "Note: could not remove this run's admin token; it expires on its own.")

    print("\n" + "=" * 64)
    print(f"Role : {role_name}  ({'view and act' if actions else 'view only'})")
    if username:
        print(f"User : {username}  (signs in to the Morpheus web UI as usual)")
    print("=" * 64)
    print("To give the role to existing users: Administration > Users > (user) > Edit > Roles.")
    if actions:
        print("Action buttons also need 'Enable actions' ticked in the plugin settings.")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit("\nCanceled.")
