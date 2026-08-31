import base64
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

import pexpect

REPO = os.environ["GITHUB_REPOSITORY"]
RUN_ID = os.environ["GITHUB_RUN_ID"]
GH_TOKEN = os.environ["GH_TOKEN"]
PACKAGE = "com.wanttalk.phonerunner"
DISPLAY_NAME = "Phone Runner"


def run(cmd, *, check=True, capture=True, env=None, timeout=300):
    result = subprocess.run(
        cmd,
        text=True,
        capture_output=capture,
        check=False,
        env=env,
        timeout=timeout,
    )
    if check and result.returncode != 0:
        raise RuntimeError(
            f"command failed ({result.returncode}): {' '.join(cmd)}\n"
            f"stdout={result.stdout[-2000:] if result.stdout else ''}\n"
            f"stderr={result.stderr[-2000:] if result.stderr else ''}"
        )
    return result


def gh_api(path, *, method="GET", fields=None):
    cmd = ["gh", "api", path, "--method", method]
    if fields:
        for key, value in fields.items():
            cmd.extend(["-f", f"{key}={value}"])
    result = run(cmd, env={**os.environ, "GH_TOKEN": GH_TOKEN})
    return json.loads(result.stdout) if result.stdout.strip() else None


def create_issue(auth_url, session_prefix, public_key):
    body = (
        "Firebase authorization is waiting.\n\n"
        f"Session: `{session_prefix}`\n\n"
        f"Authorize URL:\n{auth_url}\n\n"
        "The authorization code must NOT be posted here in plaintext. "
        "The assistant will encrypt it with this one-time public key and post only ciphertext.\n\n"
        "```pem\n" + public_key.strip() + "\n```\n"
    )
    issue = gh_api(
        f"repos/{REPO}/issues",
        method="POST",
        fields={
            "title": f"Firebase bootstrap authorization {RUN_ID}",
            "body": body,
        },
    )
    return issue["number"]


def poll_encrypted_code(issue_number, timeout_seconds=1500):
    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        comments = gh_api(f"repos/{REPO}/issues/{issue_number}/comments?per_page=100") or []
        for comment in comments:
            body = (comment.get("body") or "").strip()
            if body.startswith("ENC:"):
                return body[4:].strip()
        time.sleep(5)
    raise TimeoutError("authorization code was not received before timeout")


def decrypt_code(ciphertext_b64, private_key_path):
    enc_path = Path("/tmp/firebase-auth-code.bin")
    enc_path.write_bytes(base64.b64decode(ciphertext_b64))
    result = run(
        [
            "openssl", "pkeyutl", "-decrypt",
            "-inkey", private_key_path,
            "-in", str(enc_path),
            "-pkeyopt", "rsa_padding_mode:oaep",
            "-pkeyopt", "rsa_oaep_md:sha256",
        ]
    )
    code = result.stdout.strip()
    if not code or len(code) > 4096:
        raise RuntimeError("decrypted authorization code is invalid")
    return code


def firebase_login():
    private_key = "/tmp/firebase-bootstrap-input-private.pem"
    public_key = "/tmp/firebase-bootstrap-input-public.pem"
    run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", private_key])
    run(["openssl", "pkey", "-in", private_key, "-pubout", "-out", public_key])
    pub = Path(public_key).read_text()

    child = pexpect.spawn(
        "firebase",
        ["login", "--no-localhost", "--reauth"],
        encoding="utf-8",
        timeout=120,
    )

    auth_url = None
    session_prefix = None
    transcript = ""
    while True:
        idx = child.expect([
            r"Allow Firebase to collect[^\n]*\?",
            r"Session ID:\s*\n?\s*([A-Z0-9]{5})",
            r"https://auth\.firebase\.tools/login\?[^\s]+",
            r"Enter authorization code:",
            r"Paste authorization code here:",
            pexpect.EOF,
            pexpect.TIMEOUT,
        ])
        transcript += child.before or ""
        if isinstance(child.after, str):
            transcript += child.after

        if idx == 0:
            child.sendline("N")
        elif idx == 1:
            session_prefix = child.match.group(1)
        elif idx == 2:
            auth_url = child.match.group(0)
        elif idx in (3, 4):
            if not auth_url:
                match = re.search(r"https://auth\.firebase\.tools/login\?[^\s]+", transcript)
                if match:
                    auth_url = match.group(0)
            if not session_prefix:
                match = re.search(r"Session ID:\s*([A-Z0-9]{5})", transcript)
                if match:
                    session_prefix = match.group(1)
            if not auth_url:
                raise RuntimeError("Firebase CLI did not expose an authorization URL")
            issue_number = create_issue(auth_url, session_prefix or "unknown", pub)
            print(f"AUTH_ISSUE={issue_number}", flush=True)
            encrypted = poll_encrypted_code(issue_number)
            code = decrypt_code(encrypted, private_key)
            child.sendline(code)
            child.expect(pexpect.EOF, timeout=180)
            output = (child.before or "")
            if child.exitstatus not in (0, None):
                raise RuntimeError("Firebase login failed")
            if "Success" not in output and "logged in" not in output.lower():
                # The CLI may localize or vary the final success message; verify with projects:list.
                run(["firebase", "projects:list", "--json"], timeout=120)
            return issue_number
        elif idx == 5:
            raise RuntimeError("Firebase login exited before authorization")
        else:
            raise TimeoutError("Firebase login timed out")


def find_app_id(obj):
    if isinstance(obj, dict):
        for key, value in obj.items():
            if key in ("appId", "app_id") and isinstance(value, str) and value:
                return value
            found = find_app_id(value)
            if found:
                return found
    elif isinstance(obj, list):
        for item in obj:
            found = find_app_id(item)
            if found:
                return found
    return None


def parse_json_from_output(text):
    text = text.strip()
    try:
        return json.loads(text)
    except Exception:
        start = text.find("{")
        end = text.rfind("}")
        if start >= 0 and end > start:
            return json.loads(text[start:end+1])
        raise


def create_firebase_project_and_app(issue_number):
    suffix = RUN_ID[-8:]
    project_id = f"wtr-phone-{suffix}".lower()

    result = run([
        "firebase", "projects:create", project_id,
        "--display-name", DISPLAY_NAME,
        "--json",
    ], timeout=420)
    project_json = parse_json_from_output(result.stdout)

    app_result = run([
        "firebase", "apps:create",
        "-a", PACKAGE,
        "android", DISPLAY_NAME,
        "--project", project_id,
        "--json",
    ], timeout=240)
    app_json = parse_json_from_output(app_result.stdout)
    app_id = find_app_id(app_json)
    if not app_id:
        listing = run(["firebase", "apps:list", "--project", project_id, "--json"], timeout=120)
        app_id = find_app_id(parse_json_from_output(listing.stdout))
    if not app_id:
        raise RuntimeError("Firebase Android app was created but app ID could not be determined")

    sdk = run([
        "firebase", "apps:sdkconfig", "android", app_id,
        "--project", project_id,
    ], timeout=120).stdout.strip()
    sdk_json = parse_json_from_output(sdk)
    Path("/tmp/google-services.json").write_text(json.dumps(sdk_json, ensure_ascii=False, indent=2))

    project_info = sdk_json.get("project_info", {})
    clients = sdk_json.get("client", [])
    client = clients[0] if clients else {}
    api_keys = client.get("api_key", []) if isinstance(client, dict) else []
    api_key = api_keys[0].get("current_key", "") if api_keys else ""
    app_value = client.get("client_info", {}).get("mobilesdk_app_id", "") if isinstance(client, dict) else ""
    sender_id = str(project_info.get("project_number", ""))

    env_file = os.environ.get("GITHUB_ENV")
    if env_file:
        with open(env_file, "a", encoding="utf-8") as handle:
            handle.write(f"FIREBASE_PROJECT_ID={project_id}\n")
            handle.write(f"FIREBASE_APPLICATION_ID={app_value}\n")
            handle.write(f"FIREBASE_SENDER_ID={sender_id}\n")
            handle.write(f"FIREBASE_API_KEY={api_key}\n")

    summary = (
        "Firebase project and Android app created successfully.\n\n"
        f"Project ID: `{project_id}`\n\n"
        f"Firebase App ID: `{app_id}`\n\n"
        f"Android package: `{PACKAGE}`\n\n"
        "The Android Firebase config is non-secret. The configured APK will be built by this workflow next."
    )
    gh_api(
        f"repos/{REPO}/issues/{issue_number}/comments",
        method="POST",
        fields={"body": summary},
    )


if __name__ == "__main__":
    issue = firebase_login()
    create_firebase_project_and_app(issue)
    print("FIREBASE_BOOTSTRAP_OK", flush=True)
