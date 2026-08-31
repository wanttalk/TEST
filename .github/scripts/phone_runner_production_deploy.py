import base64
import json
import os
import re
import secrets
import subprocess
import time
from pathlib import Path

import pexpect

REPO = os.environ["GITHUB_REPOSITORY"]
RUN_ID = os.environ["GITHUB_RUN_ID"]
GH_TOKEN = os.environ["GH_TOKEN"]
PROJECT_ID = "wtr-phone-55248245"
SERVICE_ACCOUNT_ID = "phone-runner-fcm"
ASSISTANT_PUBLIC_KEY = r'''-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAxZwxayFIE70Qb4TeBS48
6vip79OyZSbeweXk2Q6eNI/tLJW/RVJsJMr4AruqU/PqPlYZCKZ2qadUhFlZwJ/u
eQkPdqQm+4gAzrxL8JJNutc7E3H0vG3krFvsoxTEMrj8btO3VnSNPFrDxm1kPJNd
DsS+tMQQydXS8iJpIrJufyBLpE2gCxdqyGpvyam67CQVWktZP858uRbq+4QAKnzE
uHONQGaPdq1K10PGYi8EJgF690N1xfF3ujP/fUFdmglKJV4vlIkVQFr0ld5q5NpD
x5Md1p2OFvndrNr2dHdgCj/OdLKclhxQ+oZnm+GK1z5lYxmNqZDJnO3hPw3QVeAk
JQIDAQAB
-----END PUBLIC KEY-----'''


def run(cmd, *, check=True, env=None, input_text=None, timeout=300, cwd=None):
    result = subprocess.run(
        cmd,
        text=True,
        input=input_text,
        capture_output=True,
        check=False,
        env=env,
        timeout=timeout,
        cwd=cwd,
    )
    if check and result.returncode != 0:
        raise RuntimeError(
            f"command failed ({result.returncode}): {' '.join(cmd)}\n"
            f"stdout={result.stdout[-3000:] if result.stdout else ''}\n"
            f"stderr={result.stderr[-3000:] if result.stderr else ''}"
        )
    return result


def gh_api(path, *, method="GET", fields=None):
    cmd = ["gh", "api", path, "--method", method]
    if fields:
        for key, value in fields.items():
            cmd.extend(["-f", f"{key}={value}"])
    result = run(cmd, env={**os.environ, "GH_TOKEN": GH_TOKEN})
    return json.loads(result.stdout) if result.stdout.strip() else None


def issue_comment(issue, body):
    gh_api(
        f"repos/{REPO}/issues/{issue}/comments",
        method="POST",
        fields={"body": body},
    )


def create_issue(auth_url, session_prefix, public_key):
    body = (
        "Phone Runner production deployment is waiting for Google authorization.\n\n"
        f"Firebase project: `{PROJECT_ID}`\n\n"
        f"Session: `{session_prefix}`\n\n"
        f"Authorize URL:\n{auth_url}\n\n"
        "Do not post the authorization code to GitHub in plaintext. "
        "Send it to ChatGPT; ChatGPT will encrypt it with this one-time key.\n\n"
        "```pem\n" + public_key.strip() + "\n```\n"
    )
    issue = gh_api(
        f"repos/{REPO}/issues",
        method="POST",
        fields={
            "title": f"Phone Runner production deploy {RUN_ID}",
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
            if body.startswith("GOOGLE_ENC:"):
                return body[len("GOOGLE_ENC:"):].strip()
        time.sleep(5)
    raise TimeoutError("Google authorization code was not received before timeout")


def decrypt_code(ciphertext_b64, private_key_path):
    enc_path = Path("/tmp/google-auth-code.bin")
    enc_path.write_bytes(base64.b64decode(ciphertext_b64))
    result = run([
        "openssl", "pkeyutl", "-decrypt",
        "-inkey", private_key_path,
        "-in", str(enc_path),
        "-pkeyopt", "rsa_padding_mode:oaep",
        "-pkeyopt", "rsa_oaep_md:sha256",
    ])
    code = result.stdout.strip()
    if not code or len(code) > 4096:
        raise RuntimeError("decrypted Google authorization code is invalid")
    return code


def firebase_login():
    private_key = "/tmp/google-input-private.pem"
    public_key = "/tmp/google-input-public.pem"
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
    issue_number = None

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
                m = re.search(r"https://auth\.firebase\.tools/login\?[^\s]+", transcript)
                if m:
                    auth_url = m.group(0)
            if not session_prefix:
                m = re.search(r"Session ID:\s*([A-Z0-9]{5})", transcript)
                if m:
                    session_prefix = m.group(1)
            if not auth_url:
                raise RuntimeError("Firebase CLI did not expose an authorization URL")
            issue_number = create_issue(auth_url, session_prefix or "unknown", pub)
            print(f"AUTH_ISSUE={issue_number}", flush=True)
            encrypted = poll_encrypted_code(issue_number)
            child.sendline(decrypt_code(encrypted, private_key))
            child.expect(pexpect.EOF, timeout=180)
            if child.exitstatus not in (0, None):
                raise RuntimeError("Firebase login failed")
            run(["firebase", "projects:list", "--json"], timeout=120)
            return issue_number
        elif idx == 5:
            raise RuntimeError("Firebase login exited before authorization")
        else:
            raise TimeoutError("Firebase login timed out")


def firebase_access_token():
    config_path = Path.home() / ".config" / "configstore" / "firebase-tools.json"
    data = json.loads(config_path.read_text())
    token = (data.get("tokens") or {}).get("access_token")
    if not token:
        raise RuntimeError("Firebase CLI access token was not found after login")
    return token


def create_service_account(access_token):
    sa_email = f"{SERVICE_ACCOUNT_ID}@{PROJECT_ID}.iam.gserviceaccount.com"
    env = {
        **os.environ,
        "CLOUDSDK_AUTH_ACCESS_TOKEN": access_token,
        "CLOUDSDK_CORE_PROJECT": PROJECT_ID,
    }
    run(["gcloud", "services", "enable", "iam.googleapis.com", "cloudresourcemanager.googleapis.com", "fcm.googleapis.com", "--project", PROJECT_ID, "--quiet"], env=env, timeout=420)

    described = run([
        "gcloud", "iam", "service-accounts", "describe", sa_email,
        "--project", PROJECT_ID,
    ], check=False, env=env, timeout=120)
    if described.returncode != 0:
        run([
            "gcloud", "iam", "service-accounts", "create", SERVICE_ACCOUNT_ID,
            "--display-name", "Phone Runner FCM sender",
            "--project", PROJECT_ID,
            "--quiet",
        ], env=env, timeout=180)

    run([
        "gcloud", "projects", "add-iam-policy-binding", PROJECT_ID,
        "--member", f"serviceAccount:{sa_email}",
        "--role", "roles/firebasecloudmessaging.admin",
        "--condition=None",
        "--quiet",
    ], env=env, timeout=240)

    key_path = "/tmp/phone-runner-fcm-service-account.json"
    run([
        "gcloud", "iam", "service-accounts", "keys", "create", key_path,
        "--iam-account", sa_email,
        "--project", PROJECT_ID,
        "--quiet",
    ], env=env, timeout=180)
    return Path(key_path)


def cloudflare_login(issue_number):
    child = pexpect.spawn(
        "npx",
        ["wrangler@4.119.0", "login", "--device", "--browser=false"],
        encoding="utf-8",
        timeout=360,
    )
    transcript = ""
    auth_url = None
    while True:
        idx = child.expect([
            r"https://dash\.cloudflare\.com/oauth2/device\?user_code=[A-Z0-9-]+",
            r"Successfully logged in",
            pexpect.EOF,
            pexpect.TIMEOUT,
        ])
        transcript += child.before or ""
        if isinstance(child.after, str):
            transcript += child.after
        if idx == 0:
            auth_url = child.match.group(0)
            issue_comment(
                issue_number,
                "Cloudflare authorization is ready. Open this link and approve within 5 minutes:\n\n" + auth_url,
            )
            print(f"CLOUDFLARE_AUTH_URL={auth_url}", flush=True)
        elif idx == 1:
            child.expect(pexpect.EOF, timeout=30)
            return auth_url
        elif idx == 2:
            if child.exitstatus == 0 and "Successfully logged in" in transcript:
                return auth_url
            raise RuntimeError("Wrangler device login exited before success")
        else:
            raise TimeoutError("Wrangler device login timed out")


def wrangler_secret(name, value):
    result = run(
        ["npx", "wrangler@4.119.0", "secret", "put", name],
        input_text=value + "\n",
        timeout=180,
        cwd="phone-runner-worker",
    )
    return result


def deploy_worker(service_account_path, issue_number):
    service = json.loads(service_account_path.read_text())
    device_registration_token = secrets.token_urlsafe(36)
    wake_api_token = secrets.token_urlsafe(36)
    github_webhook_secret = secrets.token_urlsafe(36)

    run(["npm", "install", "--no-audit", "--no-fund"], cwd="phone-runner-worker", timeout=300)
    deploy = run(["npx", "wrangler@4.119.0", "deploy"], cwd="phone-runner-worker", timeout=420)
    combined = (deploy.stdout or "") + "\n" + (deploy.stderr or "")
    urls = re.findall(r"https://[A-Za-z0-9._-]+\.workers\.dev", combined)
    if not urls:
        raise RuntimeError("Worker deployed but workers.dev URL could not be determined")
    worker_url = urls[-1].rstrip("/")

    wrangler_secret("FIREBASE_PROJECT_ID", PROJECT_ID)
    wrangler_secret("FIREBASE_CLIENT_EMAIL", service["client_email"])
    wrangler_secret("FIREBASE_PRIVATE_KEY", service["private_key"])
    wrangler_secret("DEVICE_REGISTRATION_TOKEN", device_registration_token)
    wrangler_secret("WAKE_API_TOKEN", wake_api_token)
    wrangler_secret("GITHUB_WEBHOOK_SECRET", github_webhook_secret)

    health = run(["curl", "--fail", "--silent", "--show-error", worker_url + "/health"], timeout=60)
    health_json = json.loads(health.stdout)
    if health_json.get("ok") is not True:
        raise RuntimeError("Worker health check failed")

    return worker_url, device_registration_token, wake_api_token, github_webhook_secret


def encrypt_for_assistant(value):
    pub_path = "/tmp/assistant-output-public.pem"
    Path(pub_path).write_text(ASSISTANT_PUBLIC_KEY)
    plain_path = "/tmp/secret-plain.txt"
    enc_path = "/tmp/secret-encrypted.bin"
    Path(plain_path).write_text(value)
    run([
        "openssl", "pkeyutl", "-encrypt",
        "-pubin", "-inkey", pub_path,
        "-in", plain_path,
        "-out", enc_path,
        "-pkeyopt", "rsa_padding_mode:oaep",
        "-pkeyopt", "rsa_oaep_md:sha256",
    ])
    return base64.b64encode(Path(enc_path).read_bytes()).decode()


def publish_result(issue_number, worker_url, device_token, wake_token, webhook_secret):
    body = (
        "Phone Runner Worker deployment completed.\n\n"
        f"Worker URL: {worker_url}\n\n"
        "Secrets below are encrypted for ChatGPT and are not plaintext credentials.\n\n"
        f"DEVICE_REGISTRATION_TOKEN_ENC:{encrypt_for_assistant(device_token)}\n\n"
        f"WAKE_API_TOKEN_ENC:{encrypt_for_assistant(wake_token)}\n\n"
        f"GITHUB_WEBHOOK_SECRET_ENC:{encrypt_for_assistant(webhook_secret)}"
    )
    issue_comment(issue_number, body)


def main():
    issue = firebase_login()
    access_token = firebase_access_token()
    service_account_path = create_service_account(access_token)
    issue_comment(issue, "FCM service account created. Next: Cloudflare device authorization.")
    cloudflare_login(issue)
    worker_url, device_token, wake_token, webhook_secret = deploy_worker(service_account_path, issue)
    publish_result(issue, worker_url, device_token, wake_token, webhook_secret)
    try:
        service_account_path.unlink()
    except FileNotFoundError:
        pass
    print("PHONE_RUNNER_PRODUCTION_DEPLOY_OK", flush=True)


if __name__ == "__main__":
    main()
