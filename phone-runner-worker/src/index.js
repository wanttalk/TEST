const TOKEN_URL = "https://oauth2.googleapis.com/token";
const FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
const EXPECTED_REPOSITORY = "wanttalk/android-phone-runner";
const WATCHED_PATH = "remote_request.json";
const DEVICE_TOKEN_KEY = "fcm_token";
const DEVICE_META_KEY = "device_meta";
const DEVICE_AUTH_KEY = "device_auth_token";
const PHONE_STATUS_KEY = "phone_status";
const REPORT_PHASES = new Set([
  "received",
  "dispatched",
  "dispatch_blocked",
  "dispatch_failed",
  "result",
]);

let cachedAccessToken = null;
let cachedAccessTokenExpiresAt = 0;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "POST" && url.pathname === "/github") return handleGithubWebhook(request, env);
    if (request.method === "POST" && url.pathname === "/wake") return handleManualWake(request, env);
    if (request.method === "POST" && url.pathname === "/register") return handleDeviceRegistration(request, env);
    if (request.method === "POST" && url.pathname === "/report") return handlePhoneReport(request, env);
    if (request.method === "GET" && url.pathname === "/health") return handleHealth(env);
    return new Response("Not found", { status: 404 });
  },
};

async function handleGithubWebhook(request, env) {
  const raw = await request.text();
  const signature = request.headers.get("x-hub-signature-256") || "";
  if (!await verifyGithubSignature(raw, signature, env.GITHUB_WEBHOOK_SECRET)) return new Response("Invalid signature", { status: 401 });
  if (request.headers.get("x-github-event") !== "push") return json({ ok: true, ignored: "event" });
  const payload = JSON.parse(raw);
  if (payload?.repository?.full_name !== EXPECTED_REPOSITORY) return json({ ok: true, ignored: "repository" });
  const commits = Array.isArray(payload.commits) ? [...payload.commits] : [];
  if (payload.head_commit) commits.push(payload.head_commit);
  const touched = commits.some((commit) => {
    const paths = [...(commit.added || []), ...(commit.modified || []), ...(commit.removed || [])];
    return paths.includes(WATCHED_PATH);
  });
  if (!touched) return json({ ok: true, ignored: "path" });
  const deliveryId = request.headers.get("x-github-delivery") || payload.after || crypto.randomUUID();
  const schedule = parseScheduleCommit(payload);
  const requestId = schedule?.requestId || deliveryId;
  if (schedule) {
    await sendWake(env, requestId, "github", {
      action: "schedule_set",
      interval_minutes: String(schedule.intervalMinutes),
    });
    return json({ ok: true, wake: requestId, action: "schedule_set" });
  }
  await sendWake(env, requestId, "github");
  return json({ ok: true, wake: requestId, action: "wake" });
}

function parseScheduleCommit(payload) {
  const message = typeof payload?.head_commit?.message === "string"
    ? payload.head_commit.message.trim()
    : "";
  if (!/\\brunner_schedule_set\\b/i.test(message)) return null;

  const intervalMatch = /\\binterval_minutes=(\\d+)\\b/i.exec(message);
  const requestMatch = /\\brequest_id=(\\S+)/i.exec(message);
  if (!intervalMatch || !requestMatch) return null;

  const intervalMinutes = Number(intervalMatch[1]);
  const requestId = requestMatch[1].replace(/[,);.]+$/g, "").slice(0, 220);
  if (!Number.isInteger(intervalMinutes) || intervalMinutes < 1 || intervalMinutes > 10080) return null;
  if (!requestId) return null;
  return { intervalMinutes, requestId };
}

async function handleManualWake(request, env) {
  if (!env.WAKE_API_TOKEN) return new Response("Manual wake is not configured", { status: 503 });
  const auth = request.headers.get("authorization") || "";
  if (!constantTimeEqual(auth, `Bearer ${env.WAKE_API_TOKEN}`)) return new Response("Unauthorized", { status: 401 });
  let body = {};
  try { body = await request.json(); } catch (_) {}
  const requestId = typeof body.request_id === "string" && body.request_id ? body.request_id : `manual-${crypto.randomUUID()}`;
  await sendWake(env, requestId, "manual");
  return json({ ok: true, wake: requestId });
}

async function handleDeviceRegistration(request, env) {
  const configError = registrationConfigError(env);
  if (configError) return configError;
  let body;
  try { body = await request.json(); } catch (_) { return new Response("Invalid JSON", { status: 400 }); }

  const token = typeof body?.token === "string" ? body.token.trim() : "";
  const deviceId = safeMetadata(body?.device_id, 160);
  const model = safeMetadata(body?.model, 160);
  if (token.length < 20 || token.length > 4096) return new Response("Invalid device token", { status: 400 });
  if (deviceId.length < 16) return new Response("Invalid device id", { status: 400 });

  const bearer = bearerToken(request);
  const pairedDeviceId = await getPairedDeviceId(env);
  let deviceAuthToken = "";
  if (pairedDeviceId) {
    if (pairedDeviceId !== deviceId) return new Response("A different phone is already paired", { status: 409 });
    deviceAuthToken = await getDeviceAuthToken(env);
    if (!deviceAuthToken || !constantTimeEqual(bearer, deviceAuthToken)) return new Response("Unauthorized", { status: 401 });
  } else {
    // Personal single-device mode: first registration claims an empty registry.
    // The issued device token protects all later registration rotations and reports.
    deviceAuthToken = randomSecret();
    await env.DEVICE_STATE.put(DEVICE_AUTH_KEY, deviceAuthToken);
  }

  await env.DEVICE_STATE.put(DEVICE_TOKEN_KEY, token);
  await env.DEVICE_STATE.put(DEVICE_META_KEY, JSON.stringify({ device_id: deviceId, model, registered_at: new Date().toISOString() }));
  return json({ ok: true, registered: true, device_token: deviceAuthToken });
}

async function handlePhoneReport(request, env) {
  const kvError = kvConfigError(env);
  if (kvError) return kvError;
  let body;
  try { body = await request.json(); } catch (_) { return new Response("Invalid JSON", { status: 400 }); }

  const deviceId = safeMetadata(body?.device_id, 160);
  const pairedDeviceId = await getPairedDeviceId(env);
  if (!pairedDeviceId) return new Response("No paired phone", { status: 409 });
  if (!deviceId || deviceId !== pairedDeviceId) return new Response("Wrong phone", { status: 409 });

  const deviceAuthToken = await getDeviceAuthToken(env);
  if (!deviceAuthToken || !constantTimeEqual(bearerToken(request), deviceAuthToken)) return new Response("Unauthorized", { status: 401 });

  const phase = safeMetadata(body?.phase, 40);
  if (!REPORT_PHASES.has(phase)) return new Response("Invalid report phase", { status: 400 });
  const requestId = safeMetadata(body?.request_id, 220);
  const priority = safeMetadata(body?.priority, 16);
  if (priority && priority !== "HIGH" && priority !== "NORMAL") return new Response("Invalid priority", { status: 400 });
  const ok = typeof body?.ok === "boolean" ? body.ok : null;
  const err = Number.isInteger(body?.err) ? body.err : null;
  const exitCode = Number.isInteger(body?.exit_code) ? body.exit_code : null;
  await env.DEVICE_STATE.put(PHONE_STATUS_KEY, JSON.stringify({ request_id: requestId, phase, priority, ok, err, exit_code: exitCode, at: new Date().toISOString() }));
  return json({ ok: true, reported: true });
}

async function handleHealth(env) {
  let registered = false;
  let tokenSource = "none";
  let phoneStatus = null;
  if (env.DEVICE_STATE && typeof env.DEVICE_STATE.get === "function") {
    const token = await env.DEVICE_STATE.get(DEVICE_TOKEN_KEY);
    if (token) { registered = true; tokenSource = "kv"; }
    const phoneStatusRaw = await env.DEVICE_STATE.get(PHONE_STATUS_KEY);
    if (phoneStatusRaw) {
      try { phoneStatus = JSON.parse(phoneStatusRaw); } catch (_) {}
    }
  }
  if (!registered && env.FCM_DEVICE_TOKEN) { registered = true; tokenSource = "secret-fallback"; }
  return json({
    ok: true,
    service: "phone-runner-wake",
    device_registered: registered,
    token_source: tokenSource,
    phone_last_seen_at: phoneStatus?.at || null,
    phone_phase: phoneStatus?.phase || null,
    phone_ok: typeof phoneStatus?.ok === "boolean" ? phoneStatus.ok : null,
    phone_priority: phoneStatus?.priority || null,
    phone_exit_code: Number.isInteger(phoneStatus?.exit_code) ? phoneStatus.exit_code : null,
  });
}

async function sendWake(env, requestId, source, data = { action: "wake" }) {
  requireSecret(env.FIREBASE_PROJECT_ID, "FIREBASE_PROJECT_ID");
  requireSecret(env.FIREBASE_CLIENT_EMAIL, "FIREBASE_CLIENT_EMAIL");
  requireSecret(env.FIREBASE_PRIVATE_KEY, "FIREBASE_PRIVATE_KEY");
  const deviceToken = await getDeviceToken(env);
  const accessToken = await getAccessToken(env);
  const endpoint = `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(env.FIREBASE_PROJECT_ID)}/messages:send`;
  const response = await fetch(endpoint, {
    method: "POST",
    headers: { authorization: `Bearer ${accessToken}`, "content-type": "application/json" },
    body: JSON.stringify({ message: { token: deviceToken, data: { ...data, request_id: requestId, source }, android: { priority: "HIGH", ttl: "60s" } } }),
  });
  if (!response.ok) throw new Error(`FCM ${response.status}: ${(await response.text()).slice(0, 500)}`);
}

async function getDeviceToken(env) {
  if (env.DEVICE_STATE && typeof env.DEVICE_STATE.get === "function") {
    const token = await env.DEVICE_STATE.get(DEVICE_TOKEN_KEY);
    if (token) return token;
  }
  if (env.FCM_DEVICE_TOKEN) return env.FCM_DEVICE_TOKEN;
  throw new Error("No registered FCM device token");
}

async function getPairedDeviceId(env) {
  const raw = await env.DEVICE_STATE.get(DEVICE_META_KEY);
  if (!raw) return "";
  try {
    const meta = JSON.parse(raw);
    return typeof meta?.device_id === "string" ? meta.device_id : "";
  } catch (_) { throw new Error("Device registry metadata is invalid"); }
}

async function getDeviceAuthToken(env) {
  return (await env.DEVICE_STATE.get(DEVICE_AUTH_KEY)) || "";
}

function registrationConfigError(env) {
  return kvConfigError(env);
}

function kvConfigError(env) {
  if (!env.DEVICE_STATE || typeof env.DEVICE_STATE.get !== "function" || typeof env.DEVICE_STATE.put !== "function") {
    return new Response("Device registry KV is not configured", { status: 503 });
  }
  return null;
}

function bearerToken(request) {
  const auth = request.headers.get("authorization") || "";
  return auth.startsWith("Bearer ") ? auth.slice(7) : "";
}

async function getAccessToken(env) {
  const now = Math.floor(Date.now() / 1000);
  if (cachedAccessToken && cachedAccessTokenExpiresAt - 60 > now) return cachedAccessToken;
  const assertion = await createServiceAccountJwt(env, now);
  const response = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }),
  });
  if (!response.ok) throw new Error(`OAuth ${response.status}: ${(await response.text()).slice(0, 500)}`);
  const data = await response.json();
  cachedAccessToken = data.access_token;
  cachedAccessTokenExpiresAt = now + Number(data.expires_in || 3600);
  return cachedAccessToken;
}

async function createServiceAccountJwt(env, now) {
  const header = base64UrlJson({ alg: "RS256", typ: "JWT" });
  const claims = base64UrlJson({ iss: env.FIREBASE_CLIENT_EMAIL, scope: FCM_SCOPE, aud: TOKEN_URL, iat: now, exp: now + 3600 });
  const unsigned = `${header}.${claims}`;
  const key = await crypto.subtle.importKey("pkcs8", pemToArrayBuffer(env.FIREBASE_PRIVATE_KEY), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned));
  return `${unsigned}.${base64UrlBytes(new Uint8Array(signature))}`;
}

async function verifyGithubSignature(body, signature, secret) {
  if (!secret || !signature.startsWith("sha256=")) return false;
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const digest = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(body));
  return constantTimeEqual(`sha256=${toHex(new Uint8Array(digest))}`, signature);
}

function constantTimeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i += 1) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function randomSecret() {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return base64UrlBytes(bytes);
}

function pemToArrayBuffer(pem) {
  const normalized = pem.replace(/\\n/g, "\n");
  const base64 = normalized.replace(/-----BEGIN PRIVATE KEY-----/g, "").replace(/-----END PRIVATE KEY-----/g, "").replace(/\s/g, "");
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}
function base64UrlJson(value) { return base64UrlBytes(new TextEncoder().encode(JSON.stringify(value))); }
function base64UrlBytes(bytes) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}
function toHex(bytes) { return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join(""); }
function safeMetadata(value, limit) { return typeof value === "string" ? value.trim().slice(0, limit) : ""; }
function requireSecret(value, name) { if (!value) throw new Error(`Missing Worker secret: ${name}`); }
function json(value, status = 200) { return new Response(JSON.stringify(value), { status, headers: { "content-type": "application/json; charset=utf-8" } }); }
