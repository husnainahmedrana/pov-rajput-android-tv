// POV Rajput account backup — Cloudflare Worker + D1.
//
// What the server can and cannot read:
//  - Passwords and recovery keys never reach it. The app derives a login token from the password and sends only
//    that; the server keeps a SHA-256 of it.
//  - `secrets` (debrid keys, add-on addresses, logins) is encrypted on the device with a data key the server never
//    has. The data key is stored only locked with the password and locked with the recovery key.
//  - `settings` (Home rows, preferences, local watch progress) is encrypted with a settings key the server keeps,
//    so a password reset by email alone can still bring it back.
//  - The email address is kept to send verification and reset codes.

const MAX_LAYER = 256 * 1024;          // per layer, base64 text
const CODE_TTL = 15 * 60 * 1000;
const CODE_RESEND = 60 * 1000;
const CODE_ATTEMPTS = 5;
const RESET_TTL = 15 * 60 * 1000;
const LINK_TTL = 10 * 60 * 1000;
const LOCK_AFTER = 5;
const LOCK_FOR = 15 * 60 * 1000;
const UNUSED_FOR = 180 * 24 * 60 * 60 * 1000;

export default {
  async fetch(request, env) {
    try {
      return await route(request, env);
    } catch (error) {
      if (error instanceof Reply) return error.response;
      console.log("unhandled", error && error.stack || String(error));
      return json({ error: "The backup server had a problem. Please try again." }, 500);
    }
  },
  async scheduled(_event, env) {
    const now = Date.now();
    await env.DB.batch([
      env.DB.prepare("DELETE FROM accounts WHERE seen < ?").bind(now - UNUSED_FOR),
      env.DB.prepare("DELETE FROM codes WHERE expires < ?").bind(now),
      env.DB.prepare("DELETE FROM resets WHERE expires < ?").bind(now),
      env.DB.prepare("DELETE FROM links WHERE expires < ?").bind(now),
    ]);
  },
};

class Reply extends Error {
  constructor(response) { super("reply"); this.response = response; }
}
const fail = (status, message) => { throw new Reply(json({ error: message }, status)); };

function json(value, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "x-content-type-options": "nosniff" },
  });
}

async function route(request, env) {
  const url = new URL(request.url);
  const path = url.pathname;
  const method = request.method;

  // pov.rajput.cc: the app's website address. Until the site exists it forwards to the source repository.
  if (url.hostname === (env.WEBSITE_HOST || "pov.rajput.cc")) {
    return Response.redirect(env.SOURCE_URL || "https://github.com/husnainahmedrana/pov-rajput-android-tv", 302);
  }
  // feature.rajput.cc and bug.rajput.cc: the forms people use to reach the developer.
  const feedbackKind = { [env.FEATURE_HOST || "feature.rajput.cc"]: "feature", [env.BUG_HOST || "bug.rajput.cc"]: "bug" }[url.hostname];
  if (feedbackKind) {
    if (method === "GET" && path === "/") return feedbackPage(feedbackKind);
    if (method === "POST" && path === "/submit") { await limit(request, env, path); return submitFeedback(request, env, feedbackKind); }
    fail(404, "Not found.");
  }
  if (method === "GET" && path === "/") return landing(env);
  if (method === "GET" && /^\/l\/[A-Za-z0-9_-]{22}$/.test(path)) return linkPage();

  await limit(request, env, path);

  if (method === "POST" && path === "/v1/link") return createLink(env);
  const linkMatch = path.match(/^\/v1\/link\/([A-Za-z0-9_-]{22})$/);
  if (linkMatch && method === "GET") return readLink(env, linkMatch[1]);
  if (linkMatch && method === "POST") return fillLink(request, env, linkMatch[1]);

  if (method !== "POST") fail(404, "Not found.");
  const body = await readBody(request);
  switch (path) {
    case "/v1/signup/code": return signupCode(env, body);
    case "/v1/signup": return signup(env, body);
    case "/v1/login": return login(env, body);
    case "/v1/backup": return backup(env, body);
    case "/v1/password": return changePassword(env, body);
    case "/v1/reset/code": return resetCode(env, body);
    case "/v1/reset/verify": return resetVerify(env, body);
    case "/v1/reset": return reset(env, body);
    case "/v1/delete": return remove(env, body);
    default: fail(404, "Not found.");
  }
}

// ---------- helpers ----------

async function limit(request, env, path) {
  if (!env.LIMITER) return;
  const ip = request.headers.get("cf-connecting-ip") || "unknown";
  const group = path.startsWith("/v1/link/") && request.method === "GET" ? "poll" : "api";
  const { success } = await (group === "poll" ? env.POLL_LIMITER || env.LIMITER : env.LIMITER).limit({ key: `${group}:${ip}` });
  if (!success) fail(429, "Too many requests. Please wait a minute and try again.");
}

async function readBody(request) {
  const text = await request.text();
  if (text.length > MAX_LAYER * 2 + 16 * 1024) fail(413, "The backup is too large.");
  try {
    const value = JSON.parse(text);
    if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error();
    return value;
  } catch { fail(400, "Bad request."); }
}

function normalEmail(value) {
  const email = String(value || "").trim().toLowerCase();
  if (email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) fail(400, "Enter a valid email address.");
  return email;
}

const enc = new TextEncoder();
async function sha256(text) {
  const bytes = new Uint8Array(await crypto.subtle.digest("SHA-256", enc.encode(text)));
  return [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
}
const accountId = (email) => sha256("pov-account:v1:" + email);

function token(field, value, max = 4096) {
  const text = String(value || "");
  if (!text || text.length > max || !/^[A-Za-z0-9_\-:.=+/]+$/.test(text)) fail(400, `Bad request (${field}).`);
  return text;
}
function layer(field, value) {
  const text = value == null ? "" : String(value);
  if (text.length > MAX_LAYER) fail(413, "The backup is too large.");
  if (text && !/^v1:[A-Za-z0-9_\-+/=]+:[A-Za-z0-9_\-+/=]+$/.test(text)) fail(400, `Bad request (${field}).`);
  return text;
}

function same(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function randomToken(bytes = 16) {
  const raw = crypto.getRandomValues(new Uint8Array(bytes));
  return btoa(String.fromCharCode(...raw)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
function sixDigits() {
  const value = crypto.getRandomValues(new Uint32Array(1))[0] % 1_000_000;
  return String(value).padStart(6, "0");
}

/** Signed-in calls: checks the login token, with a lockout after repeated wrong passwords. */
async function authorised(env, body) {
  const email = normalEmail(body.email);
  const id = await accountId(email);
  const auth = token("auth", body.auth, 256);
  const row = await env.DB.prepare("SELECT * FROM accounts WHERE id = ?").bind(id).first();
  const now = Date.now();
  if (!row) fail(401, "Wrong email or password.");
  if (row.locked_until > now) {
    const minutes = Math.ceil((row.locked_until - now) / 60000);
    fail(429, `Too many wrong passwords. Try again in ${minutes} minute${minutes === 1 ? "" : "s"}.`);
  }
  if (!same(await sha256(auth), row.auth_hash)) {
    const failed = row.failed + 1;
    if (failed >= LOCK_AFTER) {
      await env.DB.prepare("UPDATE accounts SET failed = 0, locked_until = ? WHERE id = ?").bind(now + LOCK_FOR, id).run();
      fail(429, "Too many wrong passwords. Try again in 15 minutes.");
    }
    await env.DB.prepare("UPDATE accounts SET failed = ? WHERE id = ?").bind(failed, id).run();
    fail(401, "Wrong email or password.");
  }
  if (row.failed) await env.DB.prepare("UPDATE accounts SET failed = 0 WHERE id = ?").bind(id).run();
  return { email, id, row, now };
}

async function sendCode(env, email, id, purpose) {
  const now = Date.now();
  const existing = await env.DB.prepare("SELECT sent FROM codes WHERE id = ? AND purpose = ?").bind(id, purpose).first();
  if (existing && now - existing.sent < CODE_RESEND) fail(429, "A code was just sent. Wait a minute before asking for another.");
  if (!env.RESEND_API_KEY) fail(503, "Email is not set up on this backup server yet.");
  const code = sixDigits();
  await env.DB.prepare(
    "INSERT INTO codes (id, purpose, code_hash, sent, expires, attempts) VALUES (?1, ?2, ?3, ?4, ?5, 0) " +
    "ON CONFLICT(id, purpose) DO UPDATE SET code_hash = ?3, sent = ?4, expires = ?5, attempts = 0"
  ).bind(id, purpose, await sha256(`${purpose}:${code}`), now, now + CODE_TTL).run();
  const subject = purpose === "signup" ? `${code} is your POV Rajput code` : `${code} is your POV Rajput password reset code`;
  const intro = purpose === "signup" ? "Enter this code in POV Rajput to finish creating your account:"
    : "Someone (hopefully you) asked to reset the password of your POV Rajput account. Enter this code in the app:";
  const response = await fetch("https://api.resend.com/emails", {
    method: "POST",
    headers: { authorization: `Bearer ${env.RESEND_API_KEY}`, "content-type": "application/json" },
    body: JSON.stringify({
      from: env.MAIL_FROM || "POV Rajput <no-reply@rajput.cc>",
      to: [email],
      subject,
      text: `${intro}\n\n${code}\n\nThe code works for 15 minutes. If you did not ask for it, you can ignore this email.\n\nPOV Rajput · pov.rajput.cc`,
      html: `<p>${intro}</p><p style="font-size:28px;font-weight:700;letter-spacing:6px;font-family:monospace">${code}</p>` +
        `<p style="color:#666">The code works for 15 minutes. If you did not ask for it, you can ignore this email.</p>` +
        `<p style="color:#999;font-size:12px">POV Rajput · <a href="https://pov.rajput.cc" style="color:#999">pov.rajput.cc</a></p>`,
    }),
  });
  if (!response.ok) {
    console.log("resend", response.status, await response.text());
    await env.DB.prepare("DELETE FROM codes WHERE id = ? AND purpose = ?").bind(id, purpose).run();
    fail(502, "The code email could not be sent. Please try again in a few minutes.");
  }
}

async function checkCode(env, id, purpose, value) {
  const code = String(value || "").replace(/\s/g, "");
  if (!/^\d{6}$/.test(code)) fail(400, "Enter the 6-digit code from the email.");
  const row = await env.DB.prepare("SELECT * FROM codes WHERE id = ? AND purpose = ?").bind(id, purpose).first();
  const now = Date.now();
  if (!row || row.expires < now || row.attempts >= CODE_ATTEMPTS) fail(400, "This code has expired. Ask for a new one.");
  if (!same(await sha256(`${purpose}:${code}`), row.code_hash)) {
    await env.DB.prepare("UPDATE codes SET attempts = attempts + 1 WHERE id = ? AND purpose = ?").bind(id, purpose).run();
    fail(400, row.attempts + 1 >= CODE_ATTEMPTS ? "Too many wrong codes. Ask for a new one." : "That code is not right. Check the email and try again.");
  }
  await env.DB.prepare("DELETE FROM codes WHERE id = ? AND purpose = ?").bind(id, purpose).run();
}

// ---------- account endpoints ----------

async function signupCode(env, body) {
  const email = normalEmail(body.email);
  const id = await accountId(email);
  if (await env.DB.prepare("SELECT 1 FROM accounts WHERE id = ?").bind(id).first()) {
    fail(409, "An account with this email already exists. Sign in instead.");
  }
  await sendCode(env, email, id, "signup");
  return json({ ok: true });
}

async function signup(env, body) {
  const email = normalEmail(body.email);
  const id = await accountId(email);
  const values = {
    auth: token("auth", body.auth, 256),
    wrapped_pw: token("wrapped_pw", body.wrapped_pw),
    wrapped_rk: token("wrapped_rk", body.wrapped_rk),
    settings_key: token("settings_key", body.settings_key, 256),
    settings: layer("settings", body.settings),
    secrets: layer("secrets", body.secrets),
  };
  if (await env.DB.prepare("SELECT 1 FROM accounts WHERE id = ?").bind(id).first()) {
    fail(409, "An account with this email already exists. Sign in instead.");
  }
  await checkCode(env, id, "signup", body.code);
  const now = Date.now();
  await env.DB.prepare(
    "INSERT INTO accounts (id, email, auth_hash, wrapped_pw, wrapped_rk, settings_key, settings, secrets, created, updated, seen) " +
    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
  ).bind(id, email, await sha256(values.auth), values.wrapped_pw, values.wrapped_rk, values.settings_key,
    values.settings, values.secrets, now, now, now).run();
  return json({ ok: true, updated: now });
}

async function login(env, body) {
  const { id, row, now } = await authorised(env, body);
  await env.DB.prepare("UPDATE accounts SET seen = ? WHERE id = ?").bind(now, id).run();
  return json({
    wrapped_pw: row.wrapped_pw, settings_key: row.settings_key,
    settings: row.settings, secrets: row.secrets, updated: row.updated,
  });
}

async function backup(env, body) {
  const settings = layer("settings", body.settings);
  const secrets = layer("secrets", body.secrets);
  const { id, now } = await authorised(env, body);
  await env.DB.prepare("UPDATE accounts SET settings = ?, secrets = ?, updated = ?, seen = ? WHERE id = ?")
    .bind(settings, secrets, now, now, id).run();
  return json({ ok: true, updated: now });
}

async function changePassword(env, body) {
  const newAuth = token("new_auth", body.new_auth, 256);
  const wrapped = token("wrapped_pw", body.wrapped_pw);
  const { id, now } = await authorised(env, body);
  await env.DB.prepare("UPDATE accounts SET auth_hash = ?, wrapped_pw = ?, seen = ? WHERE id = ?")
    .bind(await sha256(newAuth), wrapped, now, id).run();
  return json({ ok: true });
}

async function resetCode(env, body) {
  const email = normalEmail(body.email);
  const id = await accountId(email);
  if (!await env.DB.prepare("SELECT 1 FROM accounts WHERE id = ?").bind(id).first()) {
    fail(404, "There is no POV Rajput account with this email.");
  }
  await sendCode(env, email, id, "reset");
  return json({ ok: true });
}

/** A verified code returns what the device needs to rebuild the account: everything except an opened data key. */
async function resetVerify(env, body) {
  const email = normalEmail(body.email);
  const id = await accountId(email);
  const row = await env.DB.prepare("SELECT * FROM accounts WHERE id = ?").bind(id).first();
  if (!row) fail(404, "There is no POV Rajput account with this email.");
  await checkCode(env, id, "reset", body.code);
  const resetToken = randomToken(24);
  await env.DB.prepare("INSERT INTO resets (token_hash, id, expires) VALUES (?, ?, ?)")
    .bind(await sha256(resetToken), id, Date.now() + RESET_TTL).run();
  return json({
    reset_token: resetToken, wrapped_rk: row.wrapped_rk, settings_key: row.settings_key,
    settings: row.settings, secrets: row.secrets, updated: row.updated,
  });
}

async function reset(env, body) {
  const email = normalEmail(body.email);
  const id = await accountId(email);
  const resetToken = token("reset_token", body.reset_token, 128);
  const values = {
    auth: token("auth", body.auth, 256),
    wrapped_pw: token("wrapped_pw", body.wrapped_pw),
    wrapped_rk: token("wrapped_rk", body.wrapped_rk),
    secrets: layer("secrets", body.secrets),
  };
  const tokenHash = await sha256(resetToken);
  const row = await env.DB.prepare("SELECT * FROM resets WHERE token_hash = ?").bind(tokenHash).first();
  if (!row || row.id !== id || row.expires < Date.now()) fail(400, "This reset has expired. Start again.");
  const now = Date.now();
  // With the recovery key the device re-locks the same data key, so `secrets` is unchanged. Without it the device
  // starts a new data key and sends empty secrets: the old ones can no longer be opened by anyone.
  await env.DB.batch([
    env.DB.prepare("UPDATE accounts SET auth_hash = ?, wrapped_pw = ?, wrapped_rk = ?, secrets = ?, updated = ?, seen = ?, failed = 0, locked_until = 0 WHERE id = ?")
      .bind(await sha256(values.auth), values.wrapped_pw, values.wrapped_rk, values.secrets, now, now, id),
    env.DB.prepare("DELETE FROM resets WHERE id = ?").bind(id),
  ]);
  return json({ ok: true, updated: now });
}

async function remove(env, body) {
  const { id } = await authorised(env, body);
  await env.DB.batch([
    env.DB.prepare("DELETE FROM accounts WHERE id = ?").bind(id),
    env.DB.prepare("DELETE FROM codes WHERE id = ?").bind(id),
    env.DB.prepare("DELETE FROM resets WHERE id = ?").bind(id),
  ]);
  return json({ ok: true });
}

// ---------- phone sign-in relay ----------

async function createLink(env) {
  const id = randomToken(16);
  await env.DB.prepare("INSERT INTO links (id, payload, expires) VALUES (?, '', ?)").bind(id, Date.now() + LINK_TTL).run();
  return json({ id, expires_in: LINK_TTL / 1000 });
}

async function readLink(env, id) {
  const row = await env.DB.prepare("SELECT payload, expires FROM links WHERE id = ?").bind(id).first();
  if (!row || row.expires < Date.now()) fail(410, "This sign-in code has expired.");
  if (!row.payload) return json({ pending: true });
  await env.DB.prepare("DELETE FROM links WHERE id = ?").bind(id).run();
  return json({ payload: row.payload });
}

async function fillLink(request, env, id) {
  const body = await readBody(request);
  const payload = String(body.payload || "");
  if (!payload || payload.length > 4096 || !/^[A-Za-z0-9_\-.]+$/.test(payload)) fail(400, "Bad request.");
  const result = await env.DB.prepare("UPDATE links SET payload = ? WHERE id = ? AND payload = '' AND expires > ?")
    .bind(payload, id, Date.now()).run();
  if (!result.meta.changes) fail(410, "This sign-in code has expired or was already used. Make a new one on the TV.");
  return json({ ok: true });
}

// ---------- feature requests and bug reports ----------

const FEEDBACK_PER_HOUR = 5;
const escapeHtml = (text) => String(text).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

async function submitFeedback(request, env, kind) {
  const body = await readBody(request);
  // The hidden "website" field is left empty by people and filled in by bots.
  if (String(body.website || "")) return json({ ok: true });
  const title = String(body.title || "").trim();
  const text = String(body.body || "").trim();
  const email = String(body.email || "").trim();
  const app = String(body.app || "").trim().slice(0, 200);
  if (title.length < 3 || title.length > 140) fail(400, "Give it a short title (3 to 140 characters).");
  if (text.length < 10 || text.length > 5000) fail(400, "Describe it in a few words (10 to 5,000 characters).");
  if (email && (email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email))) fail(400, "That email address doesn't look right. Leave it empty if you prefer.");
  const ipHash = await sha256("pov-feedback:" + (request.headers.get("cf-connecting-ip") || "unknown"));
  const now = Date.now();
  const recent = await env.DB.prepare("SELECT COUNT(*) AS n FROM feedback WHERE ip_hash = ? AND created > ?").bind(ipHash, now - 3600_000).first();
  if (recent && recent.n >= FEEDBACK_PER_HOUR) fail(429, "Thanks! That's a lot for one hour. Please try again later.");
  const result = await env.DB.prepare("INSERT INTO feedback (kind, title, body, email, app, ip_hash, created) VALUES (?, ?, ?, ?, ?, ?, ?)")
    .bind(kind, title, text, email, app, ipHash, now).run();
  const number = result.meta.last_row_id;
  if (env.RESEND_API_KEY && env.FEEDBACK_TO) {
    const label = kind === "bug" ? "Bug" : "Feature";
    const lines = [`${label} #${number}: ${title}`, "", text, "", `App: ${app || "not given"}`, `Reply to: ${email || "not given"}`];
    const response = await fetch("https://api.resend.com/emails", {
      method: "POST",
      headers: { authorization: `Bearer ${env.RESEND_API_KEY}`, "content-type": "application/json" },
      body: JSON.stringify({
        from: env.FEEDBACK_FROM || "POV Rajput feedback <no-reply@rajput.cc>",
        to: [env.FEEDBACK_TO],
        ...(email ? { reply_to: email } : {}),
        subject: `[POV ${label} #${number}] ${title}`,
        text: lines.join("\n"),
        html: `<h2 style="margin:0 0 12px">${label} #${number}: ${escapeHtml(title)}</h2><p style="white-space:pre-wrap">${escapeHtml(text)}</p>` +
          `<p style="color:#666">App: ${escapeHtml(app || "not given")}<br>Reply to: ${escapeHtml(email || "not given")}</p>`,
      }),
    });
    if (!response.ok) console.log("feedback mail", response.status, await response.text());
  }
  return json({ ok: true, number });
}

function feedbackPage(kind) {
  const bug = kind === "bug";
  const page = FEEDBACK_PAGE
    .replaceAll("{{TITLE}}", bug ? "Report a bug" : "Request a feature")
    .replaceAll("{{INTRO}}", bug ? "Something not working in POV Rajput? Tell us what happened and we'll look into it."
      : "Have an idea that would make POV Rajput better? Tell us about it.")
    .replaceAll("{{TITLE_HINT}}", bug ? "e.g. Subtitles stop after skipping ahead" : "e.g. Sort my add-ons by name")
    .replaceAll("{{BODY_LABEL}}", bug ? "What happened, and how can we see it too?" : "What would you like, and why?")
    .replaceAll("{{BODY_HINT}}", bug ? "What you did, what you expected, and what happened instead." : "Describe the feature and how you'd use it.")
    .replaceAll("{{OTHER}}", bug ? `<a href="https://feature.rajput.cc">Request a feature instead</a>` : `<a href="https://bug.rajput.cc">Report a bug instead</a>`);
  return new Response(page, {
    headers: {
      "content-type": "text/html; charset=utf-8", "cache-control": "no-store", "referrer-policy": "no-referrer",
      "content-security-policy": "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; form-action 'none'; base-uri 'none'",
    },
  });
}

// The app opens this page with ?app=<version · device · Android version>; it is shown on the form before sending.
const FEEDBACK_PAGE = `<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>{{TITLE}} · POV Rajput</title>
<style>
body{font:16px/1.45 system-ui,sans-serif;margin:0;background:#141414;color:#eee}
main{max-width:560px;margin:0 auto;padding:28px 16px 40px}
h1{font-size:24px;margin:0 0 6px}p{color:#aaa;margin:0 0 18px}
label{display:block;font-size:14px;margin:16px 0 6px;color:#ccc}
input,textarea{box-sizing:border-box;width:100%;padding:12px;font:inherit;border-radius:10px;border:1px solid #444;background:#1f1f1f;color:#fff}
textarea{min-height:150px;resize:vertical}
.hint{font-size:13px;color:#888;margin-top:4px}
button{margin-top:22px;width:100%;padding:13px;font-size:16px;font-weight:600;border:0;border-radius:10px;background:#e50914;color:#fff}
button:disabled{opacity:.5}
#status{margin-top:16px;min-height:22px}.ok{color:#46d369}.bad{color:#ff8a80}
.trap{position:absolute;left:-9999px}
a{color:#7cc4ff}
footer{margin-top:28px;font-size:13px;color:#777}
</style>
<main>
<h1>{{TITLE}}</h1>
<p>{{INTRO}}</p>
<form id="f">
<label for="t">Title</label><input id="t" maxlength="140" required placeholder="{{TITLE_HINT}}">
<label for="b">{{BODY_LABEL}}</label><textarea id="b" maxlength="5000" required placeholder="{{BODY_HINT}}"></textarea>
<label for="e">Your email (optional)</label><input id="e" type="email" maxlength="254" placeholder="Only if you'd like a reply">
<div class="hint">Never shown publicly. Leave it empty to send anonymously.</div>
<div id="appRow" hidden><label for="a">App details (sent with your message)</label><input id="a" maxlength="200"><div class="hint">Filled in by the app. Clear it if you prefer.</div></div>
<input class="trap" id="w" tabindex="-1" autocomplete="off" aria-hidden="true">
<button id="s" type="submit">Send</button>
</form>
<div id="status" role="status"></div>
<footer>POV Rajput · <a href="https://pov.rajput.cc">pov.rajput.cc</a> · {{OTHER}}</footer>
</main>
<script>
const app = new URLSearchParams(location.search).get("app");
if (app) { document.getElementById("a").value = app.slice(0, 200); document.getElementById("appRow").hidden = false; }
const s = document.getElementById("status");
const say = (t, c) => { s.textContent = t; s.className = c || ""; };
document.getElementById("f").addEventListener("submit", async (event) => {
  event.preventDefault();
  const button = document.getElementById("s"); button.disabled = true; say("Sending…");
  try {
    const response = await fetch("/submit", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({
      title: document.getElementById("t").value, body: document.getElementById("b").value, email: document.getElementById("e").value,
      app: document.getElementById("a").value, website: document.getElementById("w").value }) });
    const result = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(result.error || "It could not be sent. Please try again.");
    document.getElementById("f").reset(); document.getElementById("appRow").hidden = true;
    say("Thank you! Your message was sent" + (result.number ? " (#" + result.number + ")" : "") + ".", "ok");
  } catch (error) { say(error.message || String(error), "bad"); }
  button.disabled = false;
});
</script>
</html>`;

// ---------- pages ----------

function landing(env) {
  const repo = env.SOURCE_URL || "https://github.com/husnainahmedrana/pov-rajput-android-tv";
  return new Response(`<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>POV Rajput backup</title><style>body{font:16px/1.5 system-ui,sans-serif;max-width:560px;margin:48px auto;padding:0 16px;background:#141414;color:#eee}a{color:#7cc4ff}</style>
<h1>POV Rajput backup</h1><p>This server keeps encrypted backups for the POV Rajput app. Debrid keys, add-ons and logins are encrypted on your device before they are sent, and this server cannot open them.</p>
<p>The code is open source: <a href="${repo}">${repo}</a></p>`, { headers: { "content-type": "text/html; charset=utf-8" } });
}

function linkPage() {
  return new Response(LINK_PAGE, {
    headers: {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "no-store",
      "referrer-policy": "no-referrer",
      "content-security-policy": "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; form-action 'none'; base-uri 'none'",
    },
  });
}

// The TV's public key is in the URL #fragment, which browsers never send to the server. The phone encrypts the email
// and password to it (ECDH P-256 + HKDF-SHA256 + AES-GCM), so this server only relays text it cannot open.
const LINK_PAGE = `<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>POV Rajput on your TV</title>
<style>
body{font:16px/1.45 system-ui,sans-serif;margin:0;background:#141414;color:#eee}
main{max-width:420px;margin:0 auto;padding:28px 16px}
h1{font-size:22px;margin:0 0 6px}p{color:#aaa;margin:0 0 18px}
label{display:block;font-size:14px;margin:14px 0 6px;color:#ccc}
input{box-sizing:border-box;width:100%;padding:12px;font-size:16px;border-radius:10px;border:1px solid #444;background:#1f1f1f;color:#fff}
button{margin-top:22px;width:100%;padding:13px;font-size:16px;font-weight:600;border:0;border-radius:10px;background:#e50914;color:#fff}
button:disabled{opacity:.5}
#status{margin-top:16px;min-height:22px}.ok{color:#46d369}.bad{color:#ff8a80}
</style>
<main>
<h1>POV Rajput on your TV</h1>
<p>Type your POV Rajput email and password here. They are encrypted on this phone for your TV only.</p>
<form id="f">
<label for="e">Email</label><input id="e" type="email" autocomplete="username" required>
<label for="p">Password</label><input id="p" type="password" autocomplete="current-password" required minlength="8">
<button id="b" type="submit">Send to TV</button>
</form>
<div id="status" role="status"></div>
</main>
<script>
const s = document.getElementById("status");
const say = (t, c) => { s.textContent = t; s.className = c || ""; };
const b64u = (bytes) => btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\\+/g, "-").replace(/\\//g, "_").replace(/=+$/, "");
const unb64u = (text) => Uint8Array.from(atob(text.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((text.length + 3) % 4)), (c) => c.charCodeAt(0));
const id = location.pathname.split("/").pop();
const tvKey = location.hash.slice(1);
if (!crypto.subtle || !/^[A-Za-z0-9_-]{80,100}$/.test(tvKey)) { say("Scan the QR code on your TV again.", "bad"); document.getElementById("b").disabled = true; }
document.getElementById("f").addEventListener("submit", async (event) => {
  event.preventDefault();
  const button = document.getElementById("b"); button.disabled = true; say("Encrypting…");
  try {
    const tv = await crypto.subtle.importKey("raw", unb64u(tvKey), { name: "ECDH", namedCurve: "P-256" }, false, []);
    const mine = await crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, false, ["deriveBits"]);
    const shared = await crypto.subtle.deriveBits({ name: "ECDH", public: tv }, mine.privateKey, 256);
    const hkdf = await crypto.subtle.importKey("raw", shared, "HKDF", false, ["deriveKey"]);
    const key = await crypto.subtle.deriveKey({ name: "HKDF", hash: "SHA-256", salt: new Uint8Array(32), info: new TextEncoder().encode("pov-link:v1") },
      hkdf, { name: "AES-GCM", length: 256 }, false, ["encrypt"]);
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const plain = new TextEncoder().encode(JSON.stringify({ email: document.getElementById("e").value.trim(), password: document.getElementById("p").value }));
    const sealed = await crypto.subtle.encrypt({ name: "AES-GCM", iv, additionalData: new TextEncoder().encode(id) }, key, plain);
    const pub = await crypto.subtle.exportKey("raw", mine.publicKey);
    const response = await fetch("/v1/link/" + id, { method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ payload: b64u(pub) + "." + b64u(iv) + "." + b64u(sealed) }) });
    const result = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(result.error || "Could not reach the TV.");
    document.getElementById("p").value = "";
    say("Sent. Look at your TV.", "ok");
  } catch (error) { say(error.message || String(error), "bad"); button.disabled = false; }
});
</script>
</html>`;
