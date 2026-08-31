import base64
import json
import os
import re
import secrets
import subprocess
import time
import urllib.parse
import urllib.request
from pathlib import Path

import pexpect

REPO = os.environ["GITHUB_REPOSITORY"]
RUN_ID = os.environ["GITHUB_RUN_ID"]
GH_TOKEN = os.environ["GH_TOKEN"]
PROJECT_ID = "wtr-phone-55248245"
PACKAGE = "com.wanttalk.phonerunner"
WORKER_DIR = Path("phone-runner-worker")
APP_DIR = Path("phone-runner-app")
ASSISTANT_PUBLIC_KEY = """-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA4nM1pjrqfTgj4AvHhvCo
LB9dO9A9GvMtLv0YdQ+4w9eLfjt6b+wiy1vSpG4k7E7hWeoNUnS85cJdu7MCmHjT
gZ1YCJEuewPH9Jln5iNwPNeWR2yodgVDYPq0iCjD12iMz6n6vf3ZSfWAqcnAOXLw
SKqfXvFRohKrcH4lWR2KYKHTfUXwVzmv/SiiiyGX4gQLu4cXB/Yt8dsINQCqJua7
i+UG701gAxL27nA+rIAhhszbaduVUGJ/2UEdolCNtmNRGxSo5OIbaj/vEhZexKiZ
ZSQjuEb7yTtcBxqb/pI0vClbGau70ySOR2/e8k7TCQ/Ps+woEt0pzeYRGrGvVzLw
OQIDAQAB
-----END PUBLIC KEY-----
"""


def run(cmd, *, cwd=None, env=None, input_text=None, check=True, timeout=600):
    result = subprocess.run(
        cmd,
        cwd=cwd,
        env=env,
        input=input_text,
        text=True,
        capture_output=True,
        check=False,
        timeout=timeout,
    )
    if check and result.returncode != 0:
        raise RuntimeError(
            f"command failed ({result.returncode}): {' '.join(cmd)}\n"
            f"stdout={result.stdout[-2500:] if result.stdout else ''}\n"
            f"stderr={result.stderr[-2500:] if result.stderr else ''}"
        )
    return result


def gh_api(path, *, method="GET", fields=None):
    cmd = ["gh", "api", path, "--method", method]
    if fields:
        for key, value in fields.items():
            cmd.extend(["-f", f"{key}={value}"])
    result = run(cmd, env={**os.environ, "GH_TOKEN": GH_TOKEN})
    return json.loads(result.stdout) if result.stdout.strip() else None


def create_issue():
    issue = gh_api(
        f"repos/{REPO}/issues",
        method="POST",
        fields={
            "title": f"Phone Runner infrastructure authorization {RUN_ID}",
            "body": "Phone Runner infrastructure bootstrap is running. Authorization links will appear below. No long-lived secret will be posted in plaintext.",
        },
    )
    return issue["number"]


def comment(issue, body):
    gh_api(
        f"repos/{REPO}/issues/{issue}/comments",
        method="POST",
        fields={"body": body},
    )


def poll_comment(issue, prefix, timeout_seconds):
    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        comments = gh_api(f"repos/{REPO}/issues/{issue}/comments?per_page=100") or []
        for item in comments:
            body = (item.get("body") or "").strip()
            if body.startswith(prefix):
                return body[len(prefix):].strip()
        time.sleep(4)
    raise TimeoutError(f"Timed out waiting for {prefix}")


def encrypted_google_auth(issue):
    private_key = "/tmp/google-input-private.pem"
    public_key = "/tmp/google-input-public.pem"
    run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", private_key])
    run(["openssl", "pkey", "-in", private_key, "-pubout", "-out", public_key])
    pub = Path(public_key).read_text()

    child = pexpect.spawn(
        "gcloud",
        ["auth", "login", "--no-launch-browser"],
        encoding="utf-8",
        timeout=180,
    )
    transcript = ""
    auth_url = ""
    while True:
        idx = child.expect([
            r"https://accounts\.google\.com/o/oauth2/auth[^\s]+",
            r"Enter verification code:",
            r"Enter authorization code:",
            pexpect.EOF,
            pexpect.TIMEOUT,
        ])
        transcript += child.before or ""
        if isinstance(child.after, str):
            transcript += child.after
        if idx == 0:
            auth_url = child.match.group(0)
        elif idx in (1, 2):
            if not auth_url:
                match = re.search(r"https://accounts\.google\.com/o/oauth2/auth[^\s]+", transcript)
                if match:
                    auth_url = match.group(0)
            if not auth_url:
                raise RuntimeError("gcloud did not expose an authorization URL")
            comment(
                issue,
                "GOOGLE_AUTH_REQUIRED\n\n"
                f"Authorize URL:\n{auth_url}\n\n"
                "Send the verification code back to the assistant. The assistant will encrypt it before posting here.\n\n"
                "One-time encryption key:\n```pem\n" + pub.strip() + "\n```",
            )
            encrypted = poll_comment(issue, "GOOGLE_ENC:", 1200)
            enc_path = Path("/tmp/google-code.bin")
            enc_path.write_bytes(base64.b64decode(encrypted))
            decrypted = run([
                "openssl", "pkeyutl", "-decrypt",
                "-inkey", private_key,
                "-in", str(enc_path),
                "-pkeyopt", "rsa_padding_mode:oaep",
                "-pkeyopt", "rsa_oaep_md:sha256",
            ]).stdout.strip()
            if not decrypted:
                raise RuntimeError("Google authorization code decrypted empty")
            child.sendline(decrypted)
            child.expect(pexpect.EOF, timeout=180)
            if child.exitstatus not in (0, None):
                raise RuntimeError("gcloud authentication failed")
            break
        elif idx == 3:
            raise RuntimeError("gcloud exited before authentication completed")
        else:
            raise TimeoutError("gcloud authentication timed out")

    run(["gcloud", "config", "set", "project", PROJECT_ID])
    run(["gcloud", "projects", "describe", PROJECT_ID, "--format=value(projectId)"])
    comment(issue, "GOOGLE_AUTH_OK\n\nGoogle Cloud authorization completed.")


def create_fcm_service_account():
    account_id = "phone-runner-fcm"
    email = f"{account_id}@{PROJECT_ID}.iam.gserviceaccount.com"
    existing = run([
        "gcloud", "iam", "service-accounts", "describe", email,
        "--project", PROJECT_ID,
    ], check=False)
    if existing.returncode != 0:
        run([
            "gcloud", "iam", "service-accounts", "create", account_id,
            "--project", PROJECT_ID,
            "--display-name", "Phone Runner FCM Sender",
        ])

    run([
        "gcloud", "projects", "add-iam-policy-binding", PROJECT_ID,
        "--member", f"serviceAccount:{email}",
        "--role", "roles/firebasecloudmessaging.admin",
        "--condition=None",
        "--quiet",
    ])
    run([
        "gcloud", "services", "enable", "fcm.googleapis.com",
        "--project", PROJECT_ID,
        "--quiet",
    ])

    key_path = Path("/tmp/phone-runner-fcm.json")
    run([
        "gcloud", "iam", "service-accounts", "keys", "create", str(key_path),
        "--iam-account", email,
        "--project", PROJECT_ID,
        "--key-file-type", "json",
        "--quiet",
    ])
    data = json.loads(key_path.read_text())
    if not data.get("client_email") or not data.get("private_key"):
        raise RuntimeError("service account key is incomplete")
    return data


def fetch_firebase_android_config():
    access_token = run(["gcloud", "auth", "print-access-token"]).stdout.strip()
    headers = {"Authorization": f"Bearer {access_token}"}
    list_url = f"https://firebase.googleapis.com/v1beta1/projects/{PROJECT_ID}/androidApps"
    req = urllib.request.Request(list_url, headers=headers)
    with urllib.request.urlopen(req, timeout=30) as response:
        apps = json.load(response).get("apps", [])
    app = next((item for item in apps if item.get("packageName") == PACKAGE), None)
    if not app:
        raise RuntimeError("Firebase Android app not found")
    name = app["name"]
    config_url = f"https://firebase.googleapis.com/v1beta1/{name}/config"
    req = urllib.request.Request(config_url, headers=headers)
    with urllib.request.urlopen(req, timeout=30) as response:
        config_response = json.load(response)
    raw = base64.b64decode(config_response["configFileContents"])
    sdk = json.loads(raw.decode("utf-8"))
    project = sdk.get("project_info", {})
    clients = sdk.get("client", [])
    client = clients[0] if clients else {}
    keys = client.get("api_key", []) if isinstance(client, dict) else []
    return {
        "FIREBASE_PROJECT_ID": PROJECT_ID,
        "FIREBASE_APPLICATION_ID": client.get("client_info", {}).get("mobilesdk_app_id", ""),
        "FIREBASE_SENDER_ID": str(project.get("project_number", "")),
        "FIREBASE_API_KEY": keys[0].get("current_key", "") if keys else "",
    }


def cloudflare_login(issue):
    env = {**os.environ, "CI": "true"}
    child = pexpect.spawn(
        "npx",
        ["wrangler", "login", "--device", "--browser=false"],
        cwd=str(WORKER_DIR),
        env=env,
        encoding="utf-8",
        timeout=360,
    )
    transcript = ""
    posted = False
    while True:
        idx = child.expect([
            r"https://dash\.cloudflare\.com/oauth2/device\?user_code=[A-Z0-9-]+",
            r"https://dash\.cloudflare\.com/oauth2/device",
            r"([A-Z0-9]{4}-[A-Z0-9]{4})",
            r"Successfully logged in",
            pexpect.EOF,
            pexpect.TIMEOUT,
        ])
        transcript += child.before or ""
        if isinstance(child.after, str):
            transcript += child.after
        if idx in (0, 1, 2) and not posted:
            prefilled = re.search(r"https://dash\.cloudflare\.com/oauth2/device\?user_code=[A-Z0-9-]+", transcript)
            code = re.search(r"\b[A-Z0-9]{4}-[A-Z0-9]{4}\b", transcript)
            if prefilled:
                url = prefilled.group(0)
                posted = True
            elif code:
                url = "https://dash.cloudflare.com/oauth2/device?user_code=" + code.group(0)
                posted = True
            else:
                continue
            comment(
                issue,
                "CLOUDFLARE_AUTH_REQUIRED\n\n"
                f"Open this URL and approve Wrangler for the Cloudflare account that should host Phone Runner:\n{url}\n\n"
                "This device authorization expires in about five minutes.",
            )
        elif idx == 3:
            continue
        elif idx == 4:
            if child.exitstatus not in (0, None):
                raise RuntimeError("Cloudflare login failed")
            break
        elif idx == 5:
            raise TimeoutError("Cloudflare authorization timed out")
    run(["npx", "wrangler", "whoami"], cwd=WORKER_DIR, env=env)
    comment(issue, "CLOUDFLARE_AUTH_OK\n\nCloudflare authorization completed.")


def put_worker_secret(name, value):
    run(
        ["npx", "wrangler", "secret", "put", name],
        cwd=WORKER_DIR,
        env={**os.environ, "CI": "true"},
        input_text=value + "\n",
        timeout=180,
    )


def deploy_worker(service_account, issue):
    env = {**os.environ, "CI": "true"}
    first = run(["npx", "wrangler", "deploy"], cwd=WORKER_DIR, env=env, timeout=300)
    combined = (first.stdout or "") + "\n" + (first.stderr or "")
    url_match = re.search(r"https://[A-Za-z0-9.-]+\.workers\.dev", combined)
    if not url_match:
        raise RuntimeError("Worker deployed but workers.dev URL could not be determined")
    worker_url = url_match.group(0).rstrip("/")

    pairing_token = secrets.token_urlsafe(32)
    wake_token = secrets.token_urlsafe(32)
    webhook_secret = secrets.token_urlsafe(48)

    put_worker_secret("FIREBASE_PROJECT_ID", PROJECT_ID)
    put_worker_secret("FIREBASE_CLIENT_EMAIL", service_account["client_email"])
    put_worker_secret("FIREBASE_PRIVATE_KEY", service_account["private_key"])
    put_worker_secret("DEVICE_PAIRING_TOKEN", pairing_token)
    put_worker_secret("WAKE_API_TOKEN", wake_token)
    put_worker_secret("GITHUB_WEBHOOK_SECRET", webhook_secret)

    second = run(["npx", "wrangler", "deploy"], cwd=WORKER_DIR, env=env, timeout=300)
    combined += "\n" + (second.stdout or "") + "\n" + (second.stderr or "")
    url_match = re.search(r"https://[A-Za-z0-9.-]+\.workers\.dev", combined)
    if url_match:
        worker_url = url_match.group(0).rstrip("/")

    with urllib.request.urlopen(worker_url + "/health", timeout=30) as response:
        health = json.load(response)
    if not health.get("ok"):
        raise RuntimeError("Worker health check failed")

    comment(
        issue,
        "WORKER_DEPLOYED\n\n"
        f"Worker URL: {worker_url}\n\n"
        "The Firebase service-account key and Worker secrets were injected directly into Cloudflare and were not committed to GitHub.",
    )
    return worker_url, pairing_token, webhook_secret


def build_apk(firebase_config):
    env = {**os.environ, **firebase_config}
    run(
        ["gradle", ":app:assembleDebug", "--stacktrace"],
        cwd=APP_DIR,
        env=env,
        timeout=900,
    )


def publish_pair_bundle(issue, worker_url, pairing_token):
    payload = json.dumps({"url": worker_url, "code": pairing_token}, separators=(",", ":"))
    public_path = Path("/tmp/assistant-public.pem")
    public_path.write_text(ASSISTANT_PUBLIC_KEY)
    plain_path = Path("/tmp/pair.json")
    encrypted_path = Path("/tmp/pair.enc")
    plain_path.write_text(payload)
    run([
        "openssl", "pkeyutl", "-encrypt",
        "-pubin", "-inkey", str(public_path),
        "-in", str(plain_path),
        "-out", str(encrypted_path),
        "-pkeyopt", "rsa_padding_mode:oaep",
        "-pkeyopt", "rsa_oaep_md:sha256",
    ])
    ciphertext = base64.b64encode(encrypted_path.read_bytes()).decode("ascii")
    comment(
        issue,
        "PAIRING_BUNDLE_READY\n\n"
        "The pairing bundle is encrypted for the assistant. No plaintext pairing token is stored here.\n\n"
        f"PAIR_ENC:{ciphertext}",
    )


def main():
    issue = create_issue()
    print(f"AUTH_ISSUE={issue}", flush=True)
    encrypted_google_auth(issue)
    service_account = create_fcm_service_account()
    firebase_config = fetch_firebase_android_config()
    cloudflare_login(issue)
    worker_url, pairing_token, webhook_secret = deploy_worker(service_account, issue)
    build_apk(firebase_config)
    publish_pair_bundle(issue, worker_url, pairing_token)
    comment(
        issue,
        "BOOTSTRAP_READY\n\n"
        "Firebase sender, Cloudflare Worker, KV, secrets, and the Firebase-configured APK are ready. One final GitHub webhook configuration remains because the public build runner does not have administration permission on the private production repository.\n\n"
        f"Webhook URL: {worker_url}/github\n"
        "Webhook secret is intentionally not posted here; the assistant will handle the remaining safe handoff.",
    )
    print("PHONE_RUNNER_INFRA_READY", flush=True)


if __name__ == "__main__":
    main()
