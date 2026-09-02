import assert from "node:assert/strict";
import crypto from "node:crypto";
import test from "node:test";

import worker from "./src/index.js";

function githubSignature(secret, body) {
  return "sha256=" + crypto.createHmac("sha256", secret).update(body).digest("hex");
}

function memoryKv(initial = {}) {
  const values = new Map(Object.entries(initial));
  return {
    async get(key) {
      return values.has(key) ? values.get(key) : null;
    },
    async put(key, value) {
      values.set(key, String(value));
    },
    values,
  };
}

function registrationRequest({ token, deviceId, secret = "" }) {
  const headers = {
    "content-type": "application/json",
  };
  if (secret) headers.authorization = "Bearer " + secret;

  return new Request("https://runner.test/register", {
    method: "POST",
    headers,
    body: JSON.stringify({
      token,
      device_id: deviceId,
      model: "Test Phone",
    }),
  });
}

function reportRequest({
  deviceId,
  phase,
  requestId = "request-1",
  priority = "HIGH",
  ok,
  err,
  exitCode,
  secret = "pairing-secret-1234567890",
}) {
  const body = {
    device_id: deviceId,
    phase,
    request_id: requestId,
    priority,
  };
  if (ok !== undefined) body.ok = ok;
  if (err !== undefined) body.err = err;
  if (exitCode !== undefined) body.exit_code = exitCode;

  return new Request("https://runner.test/report", {
    method: "POST",
    headers: {
      authorization: `Bearer ${secret}`,
      "content-type": "application/json",
    },
    body: JSON.stringify(body),
  });
}

test("rejects an invalid GitHub webhook signature", async () => {
  const request = new Request("https://runner.test/github", {
    method: "POST",
    headers: {
      "x-github-event": "push",
      "x-hub-signature-256": "sha256=bad",
    },
    body: "{}",
  });

  const response = await worker.fetch(request, { GITHUB_WEBHOOK_SECRET: "secret" });
  assert.equal(response.status, 401);
});

test("ignores unrelated GitHub paths", async () => {
  const secret = "secret";
  const body = JSON.stringify({
    repository: { full_name: "wanttalk/android-phone-runner" },
    commits: [{ modified: ["README.md"], added: [], removed: [] }],
  });
  const request = new Request("https://runner.test/github", {
    method: "POST",
    headers: {
      "x-github-event": "push",
      "x-hub-signature-256": githubSignature(secret, body),
    },
    body,
  });

  const response = await worker.fetch(request, { GITHUB_WEBHOOK_SECRET: secret });
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { ok: true, ignored: "path" });
});

test("manual wake fails closed when its secret is missing", async () => {
  const request = new Request("https://runner.test/wake", {
    method: "POST",
    headers: { authorization: "Bearer " },
    body: "{}",
  });

  const response = await worker.fetch(request, {});
  assert.equal(response.status, 503);
});

test("device registration needs KV but not a pairing secret", async () => {
  const request = registrationRequest({
    token: "x".repeat(64),
    deviceId: "test-device-0001",
  });

  const missingKv = await worker.fetch(request.clone(), {});
  assert.equal(missingKv.status, 503);

  const first = await worker.fetch(request, {
    DEVICE_STATE: memoryKv(),
  });
  assert.equal(first.status, 200);
});

test("device registration stores the FCM token without echoing it", async () => {
  const kv = memoryKv();
  const token = "device-registration-token-" + "x".repeat(48);
  const request = registrationRequest({
    token,
    deviceId: "test-device-0001",
  });

  const response = await worker.fetch(request, {
    DEVICE_REGISTRATION_TOKEN: "pairing-secret-1234567890",
    DEVICE_STATE: kv,
  });

  assert.equal(response.status, 200);
  const responseBody = await response.text();
  assert.equal(responseBody.includes(token), false);
  assert.equal(await kv.get("fcm_token"), token);

  const meta = JSON.parse(await kv.get("device_meta"));
  assert.equal(meta.device_id, "test-device-0001");
  assert.equal(meta.model, "Test Phone");
  assert.match(meta.registered_at, /^\d{4}-\d{2}-\d{2}T/);
});

test("the paired installation may rotate its FCM token", async () => {
  const kv = memoryKv();
  const env = {
    DEVICE_REGISTRATION_TOKEN: "pairing-secret-1234567890",
    DEVICE_STATE: kv,
  };

  const first = await worker.fetch(registrationRequest({
    token: "first-fcm-token-" + "a".repeat(48),
    deviceId: "stable-device-0001",
  }), env);
  assert.equal(first.status, 200);
  const firstBody = await first.json();

  const secondToken = "rotated-fcm-token-" + "b".repeat(48);
  const second = await worker.fetch(registrationRequest({
    token: secondToken,
    deviceId: "stable-device-0001",
    secret: firstBody.device_token,
  }), env);
  assert.equal(second.status, 200);
  assert.equal(await kv.get("fcm_token"), secondToken);
});

test("a different installation cannot replace the paired phone", async () => {
  const kv = memoryKv();
  const env = {
    DEVICE_REGISTRATION_TOKEN: "pairing-secret-1234567890",
    DEVICE_STATE: kv,
  };

  const firstToken = "first-fcm-token-" + "a".repeat(48);
  const first = await worker.fetch(registrationRequest({
    token: firstToken,
    deviceId: "stable-device-0001",
  }), env);
  assert.equal(first.status, 200);

  const takeover = await worker.fetch(registrationRequest({
    token: "attacker-fcm-token-" + "z".repeat(48),
    deviceId: "different-device-02",
  }), env);
  assert.equal(takeover.status, 409);
  assert.equal(await kv.get("fcm_token"), firstToken);
});

test("only the paired phone may publish lifecycle reports", async () => {
  const kv = memoryKv({
    device_meta: JSON.stringify({ device_id: "stable-device-0001" }),
  });
  const env = {
    DEVICE_REGISTRATION_TOKEN: "pairing-secret-1234567890",
    DEVICE_STATE: kv,
  };

  const wrong = await worker.fetch(reportRequest({
    deviceId: "different-device-02",
    phase: "received",
  }), env);
  assert.equal(wrong.status, 409);
  assert.equal(await kv.get("phone_status"), null);
});

test("phone result report is stored while health omits request id and secrets", async () => {
  const kv = memoryKv({
    fcm_token: "private-device-token-never-return-this",
    device_meta: JSON.stringify({ device_id: "stable-device-0001" }),
    device_auth_token: "device-auth-token",
  });
  const env = {
    DEVICE_REGISTRATION_TOKEN: "pairing-secret-1234567890",
    DEVICE_STATE: kv,
  };

  const report = await worker.fetch(reportRequest({
    deviceId: "stable-device-0001",
    phase: "result",
    requestId: "private-request-correlation-id",
    priority: "HIGH",
    ok: true,
    err: 0,
    exitCode: 0,
    secret: "device-auth-token",
  }), env);
  assert.equal(report.status, 200);

  const stored = JSON.parse(await kv.get("phone_status"));
  assert.equal(stored.request_id, "private-request-correlation-id");
  assert.equal(stored.phase, "result");
  assert.equal(stored.ok, true);
  assert.equal(stored.exit_code, 0);

  const health = await worker.fetch(new Request("https://runner.test/health"), env);
  const healthText = await health.text();
  assert.equal(healthText.includes("private-request-correlation-id"), false);
  assert.equal(healthText.includes("private-device-token-never-return-this"), false);
  assert.equal(healthText.includes("pairing-secret-1234567890"), false);

  const healthJson = JSON.parse(healthText);
  assert.equal(healthJson.device_registered, true);
  assert.equal(healthJson.token_source, "kv");
  assert.equal(healthJson.phone_phase, "result");
  assert.equal(healthJson.phone_ok, true);
  assert.equal(healthJson.phone_priority, "HIGH");
  assert.equal(healthJson.phone_exit_code, 0);
  assert.match(healthJson.phone_last_seen_at, /^\d{4}-\d{2}-\d{2}T/);
});

test("health reports the old secret token only as a fallback", async () => {
  const response = await worker.fetch(
    new Request("https://runner.test/health"),
    { FCM_DEVICE_TOKEN: "legacy-device-token" },
  );
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), {
    ok: true,
    service: "phone-runner-wake",
    device_registered: true,
    token_source: "secret-fallback",
    phone_last_seen_at: null,
    phone_phase: null,
    phone_ok: null,
    phone_priority: null,
    phone_exit_code: null,
  });
});

test("manual wake reads KV token, creates OAuth JWT, then sends direct high-priority FCM", async () => {
  const { privateKey } = crypto.generateKeyPairSync("rsa", { modulusLength: 2048 });
  const privateKeyPem = privateKey.export({ type: "pkcs8", format: "pem" }).toString();

  const originalFetch = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (url, options = {}) => {
    calls.push({ url: String(url), options });
    if (String(url) === "https://oauth2.googleapis.com/token") {
      return new Response(JSON.stringify({
        access_token: "test-access-token",
        expires_in: 3600,
      }), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    }
    if (String(url).includes("fcm.googleapis.com")) {
      return new Response(JSON.stringify({ name: "projects/test/messages/1" }), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    }
    throw new Error("Unexpected fetch " + url);
  };

  try {
    const request = new Request("https://runner.test/wake", {
      method: "POST",
      headers: {
        authorization: "Bearer wake-secret",
        "content-type": "application/json",
      },
      body: JSON.stringify({ request_id: "unit-test-1" }),
    });

    const response = await worker.fetch(request, {
      WAKE_API_TOKEN: "wake-secret",
      FIREBASE_PROJECT_ID: "test-project",
      FIREBASE_CLIENT_EMAIL: "runner@test-project.iam.gserviceaccount.com",
      FIREBASE_PRIVATE_KEY: privateKeyPem,
      DEVICE_STATE: memoryKv({ fcm_token: "kv-device-token" }),
    });

    assert.equal(response.status, 200);
    assert.equal(calls.length, 2);
    assert.equal(calls[0].url, "https://oauth2.googleapis.com/token");
    assert.match(String(calls[0].options.body), /grant_type=/);

    const fcm = JSON.parse(calls[1].options.body);
    assert.equal(fcm.message.token, "kv-device-token");
    assert.equal(fcm.message.android.priority, "HIGH");
    assert.equal(fcm.message.android.ttl, "60s");
    assert.deepEqual(fcm.message.data, {
      action: "wake",
      request_id: "unit-test-1",
      source: "manual",
    });
    assert.equal(
      calls[1].options.headers.authorization,
      "Bearer test-access-token",
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
});


test("GitHub schedule commit sends a direct APK schedule command", async () => {
  const { privateKey } = crypto.generateKeyPairSync("rsa", { modulusLength: 2048 });
  const privateKeyPem = privateKey.export({ type: "pkcs8", format: "pem" }).toString();
  const secret = "schedule-webhook-secret";
  const calls = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (url, options = {}) => {
    calls.push({ url: String(url), options });
    if (String(url) === "https://oauth2.googleapis.com/token") {
      return new Response(JSON.stringify({
        access_token: "schedule-test-access-token",
        expires_in: 3600,
      }), { status: 200 });
    }
    if (String(url).includes("fcm.googleapis.com")) {
      return new Response(JSON.stringify({ name: "projects/test/messages/schedule" }), { status: 200 });
    }
    throw new Error("Unexpected fetch " + url);
  };

  try {
    const body = JSON.stringify({
      repository: { full_name: "wanttalk/android-phone-runner" },
      head_commit: {
        message: "test: apply runner_schedule_set interval_minutes=1 request_id=schedule-test-1",
      },
      commits: [{ modified: ["remote_request.json"] }],
    });
    const response = await worker.fetch(new Request("https://runner.test/github", {
      method: "POST",
      headers: {
        "x-github-event": "push",
        "x-hub-signature-256": githubSignature(secret, body),
        "x-github-delivery": "delivery-schedule-test",
      },
      body,
    }), {
      GITHUB_WEBHOOK_SECRET: secret,
      FIREBASE_PROJECT_ID: "test-project",
      FIREBASE_CLIENT_EMAIL: "runner@test-project.iam.gserviceaccount.com",
      FIREBASE_PRIVATE_KEY: privateKeyPem,
      DEVICE_STATE: memoryKv({ fcm_token: "schedule-device-token" }),
    });

    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), {
      ok: true,
      wake: "schedule-test-1",
      action: "schedule_set",
    });

    const fcmCall = calls.find((call) => call.url.includes("fcm.googleapis.com"));
    assert.ok(fcmCall);
    const fcm = JSON.parse(fcmCall.options.body);
    assert.deepEqual(fcm.message.data, {
      action: "schedule_set",
      interval_minutes: "1",
      request_id: "schedule-test-1",
      source: "github",
    });
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("scheduled cron sends a direct high-priority FCM wake", async () => {
  const { privateKey } = crypto.generateKeyPairSync("rsa", { modulusLength: 2048 });
  const privateKeyPem = privateKey.export({ type: "pkcs8", format: "pem" }).toString();
  const calls = [];
  const waits = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (url, options = {}) => {
    calls.push({ url: String(url), options });
    if (String(url) === "https://oauth2.googleapis.com/token") {
      return new Response(JSON.stringify({ access_token: "cron-test-access-token", expires_in: 3600 }), { status: 200 });
    }
    if (String(url).includes("fcm.googleapis.com")) {
      return new Response(JSON.stringify({ name: "projects/test/messages/cron" }), { status: 200 });
    }
    throw new Error("Unexpected fetch " + url);
  };

  try {
    const scheduledTime = 1788320000000;
    await worker.scheduled(
      { scheduledTime },
      {
        FIREBASE_PROJECT_ID: "test-project",
        FIREBASE_CLIENT_EMAIL: "runner@test-project.iam.gserviceaccount.com",
        FIREBASE_PRIVATE_KEY: privateKeyPem,
        DEVICE_STATE: memoryKv({ fcm_token: "cron-device-token" }),
      },
      { waitUntil(promise) { waits.push(promise); } },
    );

    assert.equal(waits.length, 1);
    await Promise.all(waits);
    const fcmCall = calls.find((call) => call.url.includes("fcm.googleapis.com"));
    assert.ok(fcmCall);
    const fcm = JSON.parse(fcmCall.options.body);
    assert.equal(fcm.message.token, "cron-device-token");
    assert.equal(fcm.message.android.priority, "HIGH");
    assert.deepEqual(fcm.message.data, {
      action: "wake",
      request_id: `cron-${scheduledTime}`,
      source: "cron",
    });
  } finally {
    globalThis.fetch = originalFetch;
  }
});
