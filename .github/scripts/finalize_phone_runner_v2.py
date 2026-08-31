import base64
import json
import os
import re
import secrets
import subprocess
import time
import urllib.error
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
    result = subprocess.run(cmd, cwd=cwd, env=env, input=input_text, text=True,
                            capture_output=True, check=False, timeout=timeout)
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
    issue = gh_api(f"repos/{REPO}/issues", method="POST", fields={
        "title": f"Phone Runner final authorization {RUN_ID}",
        "body": "Final Phone Runner provisioning is running. Authorization links will appear below. No long-lived secret will be posted in plaintext.",
    })
    return issue["number"]


def comment(issue, body):
    gh_api(f"repos/{REPO}/issues/{issue}/comments", method="POST", fields={"body": body})


def poll_comment(issue, prefix, timeout_seconds=1200):
    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        comments = gh_api(f"repos/{REPO}/issues/{issue}/comments?per_page=100") or []
        for item in comments:
            body = (item.get("body") or "").strip()
            if body.startswith(prefix):
                return body[len(prefix):].strip()
        time.sleep(4)
    raise TimeoutError(f"Timed out waiting for {prefix}")


def decrypt_input(ciphertext_b64, private_key_path):
    enc = Path("/tmp/auth-input.bin")
    enc.write_bytes(base64.b64decode(ciphertext_b64))
    code = run([
        "openssl", "pkeyutl", "-decrypt", "-inkey", private_key_path,
        "-in", str(enc), "-pkeyopt", "rsa_padding_mode:oaep",
        "-pkeyopt", "rsa_oaep_md:sha256",
    ]).stdout.strip()
    if not code or len(code) > 4096:
        raise RuntimeError("decrypted authorization code is invalid")
    return code


def firebase_login(issue):
    private_key = "/tmp/firebase-final-input-private.pem"
    public_key = "/tmp/firebase-final-input-public.pem"
    run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", private_key])
    run(["openssl", "pkey", "-in", private_key, "-pubout", "-out", public_key])
    pub = Path(public_key).read_text()

    child = pexpect.spawn("firebase", ["login", "--no-localhost", "--reauth"], encoding="utf-8", timeout=180)
    auth_url = ""
    transcript = ""
    while True:
        idx = child.expect([
            r"Allow Firebase to collect[^\n]*\?",
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
            auth_url = child.match.group(0)
        elif idx in (2, 3):
            if not auth_url:
                match = re.search(r"https://auth\.firebase\.tools/login\?[^\s]+", transcript)
                if match:
                    auth_url = match.group(0)
            if not auth_url:
                raise RuntimeError("Firebase CLI did not expose authorization URL")
            comment(issue,
                    "GOOGLE_AUTH_REQUIRED\n\n"
                    f"Authorize URL:\n{auth_url}\n\n"
                    "Complete Google authorization and send the returned code only to the assistant.\n\n"
                    "One-time encryption key:\n```pem\n" + pub.strip() + "\n```")
            encrypted = poll_comment(issue, "GOOGLE_ENC:")
            child.sendline(decrypt_input(encrypted, private_key))
            child.expect(pexpect.EOF, timeout=180)
            if child.exitstatus not in (0, None):
                raise RuntimeError("Firebase login failed")
            run(["firebase", "projects:list", "--json"], timeout=180)
            comment(issue, "GOOGLE_AUTH_OK\n\nGoogle authorization completed.")
            return
        elif idx == 4:
            raise RuntimeError("Firebase login exited before authorization")
        else:
            raise TimeoutError("Firebase login timed out")


def firebase_access_token():
    run(["firebase", "projects:list", "--json"], timeout=180)
    path = Path.home() / ".config" / "configstore" / "firebase-tools.json"
    if not path.exists():
        raise RuntimeError("Firebase CLI credential store not found")
    data = json.loads(path.read_text())
    token = (data.get("tokens") or {}).get("access_token") or ""
    if not token:
        raise RuntimeError("Firebase CLI access token not found")
    return token


def google_api(token, url, *, method="GET", body=None, allow=(200, 201)):
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as response:
            raw = response.read().decode("utf-8")
            return response.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        if exc.code in allow:
            return exc.code, (json.loads(raw) if raw else {})
        raise RuntimeError(f"Google API {exc.code} at {url}: {raw[:1200]}") from exc


def enable_service(token, service):
    url = f"https://serviceusage.googleapis.com/v1/projects/{PROJECT_ID}/services/{service}:enable"
    google_api(token, url, method="POST", body={}, allow=(200, 201))


def create_fcm_service_account(token):
    for service in ("iam.googleapis.com", "cloudresourcemanager.googleapis.com", "fcm.googleapis.com"):
        try:
            enable_service(token, service)
        except Exception:
            if service == "fcm.googleapis.com":
                raise

    account_id = "phone-runner-fcm"
    email = f"{account_id}@{PROJECT_ID}.iam.gserviceaccount.com"
    describe = f"https://iam.googleapis.com/v1/projects/{PROJECT_ID}/serviceAccounts/{urllib.parse.quote(email, safe='')}"
    try:
        _, account = google_api(token, describe)
    except RuntimeError as exc:
        if "Google API 404" not in str(exc):
            raise
        create_url = f"https://iam.googleapis.com/v1/projects/{PROJECT_ID}/serviceAccounts"
        _, account = google_api(token, create_url, method="POST", body={
            "accountId": account_id,
            "serviceAccount": {"displayName": "Phone Runner FCM Sender"},
        })

    get_policy = f"https://cloudresourcemanager.googleapis.com/v1/projects/{PROJECT_ID}:getIamPolicy"
    _, policy = google_api(token, get_policy, method="POST", body={})
    role = "roles/firebasecloudmessaging.admin"
    member = f"serviceAccount:{email}"
    bindings = policy.setdefault("bindings", [])
    binding = next((b for b in bindings if b.get("role") == role), None)
    if binding is None:
        binding = {"role": role, "members": []}
        bindings.append(binding)
    if member not in binding.setdefault("members", []):
        binding["members"].append(member)
        set_policy = f"https://cloudresourcemanager.googleapis.com/v1/projects/{PROJECT_ID}:setIamPolicy"
        _, policy = google_api(token, set_policy, method="POST", body={"policy": policy})

    key_url = f"https://iam.googleapis.com/v1/projects/{PROJECT_ID}/serviceAccounts/{urllib.parse.quote(email, safe='')}/keys"
    _, key = google_api(token, key_url, method="POST", body={
        "privateKeyType": "TYPE_GOOGLE_CREDENTIALS_FILE",
        "keyAlgorithm": "KEY_ALG_RSA_2048",
    })
    private_data = key.get("privateKeyData") or ""
    if not private_data:
        raise RuntimeError("Google IAM returned no service-account key")
    service_account = json.loads(base64.b64decode(private_data).decode("utf-8"))
    if not service_account.get("client_email") or not service_account.get("private_key"):
        raise RuntimeError("service account key is incomplete")
    return service_account


def firebase_android_config():
    result = run(["firebase", "apps:list", "--project", PROJECT_ID, "--json"], timeout=180)
    data = json.loads(result.stdout[result.stdout.find("{"):])
    def walk(obj):
        if isinstance(obj, dict):
            if obj.get("platform") == "ANDROID" and obj.get("packageName") == PACKAGE:
                return obj.get("appId") or obj.get("app_id")
            for value in obj.values():
                found = walk(value)
                if found: return found
        elif isinstance(obj, list):
            for item in obj:
                found = walk(item)
                if found: return found
        return None
    app_id = walk(data)
    if not app_id:
        raise RuntimeError("Firebase Android app ID not found")
    sdk_raw = run(["firebase", "apps:sdkconfig", "android", app_id, "--project", PROJECT_ID], timeout=180).stdout.strip()
    start, end = sdk_raw.find("{"), sdk_raw.rfind("}")
    sdk = json.loads(sdk_raw[start:end+1])
    info = sdk.get("project_info", {})
    client = (sdk.get("client") or [{}])[0]
    keys = client.get("api_key") or []
    return {
        "FIREBASE_PROJECT_ID": PROJECT_ID,
        "FIREBASE_APPLICATION_ID": client.get("client_info", {}).get("mobilesdk_app_id", ""),
        "FIREBASE_SENDER_ID": str(info.get("project_number", "")),
        "FIREBASE_API_KEY": keys[0].get("current_key", "") if keys else "",
    }


def cloudflare_login(issue):
    env = {**os.environ, "CI": "true"}
    child = pexpect.spawn("npx", ["wrangler", "login", "--device", "--browser=false"],
                          cwd=str(WORKER_DIR), env=env, encoding="utf-8", timeout=360)
    transcript = ""
    posted = False
    while True:
        idx = child.expect([
            r"https://dash\.cloudflare\.com/oauth2/device\?user_code=[A-Z0-9-]+",
            r"https://dash\.cloudflare\.com/oauth2/device",
            r"\b[A-Z0-9]{4}-[A-Z0-9]{4}\b",
            r"Successfully logged in",
            pexpect.EOF,
            pexpect.TIMEOUT,
        ])
        transcript += child.before or ""
        if isinstance(child.after, str): transcript += child.after
        if idx in (0, 1, 2) and not posted:
            prefilled = re.search(r"https://dash\.cloudflare\.com/oauth2/device\?user_code=[A-Z0-9-]+", transcript)
            code = re.search(r"\b[A-Z0-9]{4}-[A-Z0-9]{4}\b", transcript)
            if prefilled:
                url = prefilled.group(0)
            elif code:
                url = "https://dash.cloudflare.com/oauth2/device?user_code=" + code.group(0)
            else:
                continue
            posted = True
            comment(issue,
                    "CLOUDFLARE_AUTH_REQUIRED\n\n"
                    f"Open this URL and approve Wrangler for the Cloudflare account that should host Phone Runner:\n{url}\n\n"
                    "The device authorization expires in about five minutes.")
        elif idx == 4:
            if child.exitstatus not in (0, None):
                raise RuntimeError("Cloudflare login failed")
            break
        elif idx == 5:
            raise TimeoutError("Cloudflare authorization timed out")
    run(["npx", "wrangler", "whoami"], cwd=WORKER_DIR, env=env)
    comment(issue, "CLOUDFLARE_AUTH_OK\n\nCloudflare authorization completed.")


def put_secret(name, value):
    run(["npx", "wrangler", "secret", "put", name], cwd=WORKER_DIR,
        env={**os.environ, "CI": "true"}, input_text=value + "\n", timeout=180)


def deploy_worker(service_account, issue):
    env = {**os.environ, "CI": "true"}
    first = run(["npx", "wrangler", "deploy"], cwd=WORKER_DIR, env=env, timeout=360)
    output = (first.stdout or "") + "\n" + (first.stderr or "")
    match = re.search(r"https://[A-Za-z0-9.-]+\.workers\.dev", output)
    if not match:
        raise RuntimeError("Worker deployed but workers.dev URL could not be determined")
    worker_url = match.group(0).rstrip("/")

    pairing_code = secrets.token_urlsafe(32)
    wake_token = secrets.token_urlsafe(32)
    webhook_secret = secrets.token_urlsafe(48)
    for name, value in (
        ("FIREBASE_PROJECT_ID", PROJECT_ID),
        ("FIREBASE_CLIENT_EMAIL", service_account["client_email"]),
        ("FIREBASE_PRIVATE_KEY", service_account["private_key"]),
        ("DEVICE_PAIRING_TOKEN", pairing_code),
        ("WAKE_API_TOKEN", wake_token),
        ("GITHUB_WEBHOOK_SECRET", webhook_secret),
    ):
        put_secret(name, value)

    second = run(["npx", "wrangler", "deploy"], cwd=WORKER_DIR, env=env, timeout=360)
    output += "\n" + (second.stdout or "") + "\n" + (second.stderr or "")
    match = re.search(r"https://[A-Za-z0-9.-]+\.workers\.dev", output)
    if match: worker_url = match.group(0).rstrip("/")
    with urllib.request.urlopen(worker_url + "/health", timeout=30) as response:
        health = json.load(response)
    if not health.get("ok"):
        raise RuntimeError("Worker health check failed")
    comment(issue, f"WORKER_DEPLOYED\n\nWorker URL: {worker_url}\n\nAll long-lived secrets are stored only in Cloudflare.")
    return worker_url, pairing_code, wake_token, webhook_secret


def build_apk(firebase_config):
    run(["gradle", ":app:assembleDebug", "--stacktrace"], cwd=APP_DIR,
        env={**os.environ, **firebase_config}, timeout=900)


def encrypt_for_assistant(payload):
    pub = Path("/tmp/assistant-public.pem")
    plain = Path("/tmp/final-bundle.json")
    enc = Path("/tmp/final-bundle.enc")
    pub.write_text(ASSISTANT_PUBLIC_KEY)
    plain.write_text(json.dumps(payload, separators=(",", ":")))
    run(["openssl", "pkeyutl", "-encrypt", "-pubin", "-inkey", str(pub),
         "-in", str(plain), "-out", str(enc),
         "-pkeyopt", "rsa_padding_mode:oaep", "-pkeyopt", "rsa_oaep_md:sha256"])
    return base64.b64encode(enc.read_bytes()).decode("ascii")


def main():
    issue = create_issue()
    print(f"AUTH_ISSUE={issue}", flush=True)
    firebase_login(issue)
    access_token = firebase_access_token()
    service_account = create_fcm_service_account(access_token)
    firebase_config = firebase_android_config()
    cloudflare_login(issue)
    worker_url, pairing_code, wake_token, webhook_secret = deploy_worker(service_account, issue)
    build_apk(firebase_config)
    ciphertext = encrypt_for_assistant({
        "url": worker_url,
        "pairing_code": pairing_code,
        "wake_token": wake_token,
        "webhook_secret": webhook_secret,
    })
    comment(issue,
            "FINAL_BUNDLE_READY\n\nThe final pairing/admin bundle is encrypted for the assistant. No plaintext secret is stored here.\n\n"
            f"FINAL_ENC:{ciphertext}\n\n"
            f"Webhook URL: {worker_url}/github")
    print("PHONE_RUNNER_FINAL_READY", flush=True)


if __name__ == "__main__":
    main()
