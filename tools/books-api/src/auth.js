// Выдача токена установки приложения по аттестации ключа (docs/adr/0003, фаза 2).
//
//   GET  /v1/auth/challenge            → {challenge, expires_in}   одноразовое задание, подписанное HMAC, живёт 2 минуты
//   POST /v1/auth/register             {challenge, chain: [base64 DER, лист первым]}
//                                      → {token, expires_at, install_id}   токен `va_…` на 30 дней; продлевается повторной регистрацией
//
// Приложение создаёт ключ с этим challenge в аппаратном хранилище, телефон отдаёт цепочку до корня Google; здесь проверяются подписи цепочки,
// пакет и подпись APK по таблице app_signatures. Скрипту, доставшему адрес API из кода, телефон такую цепочку не выдаст.
import { b64url, issueAppToken, macOf, unb64url } from "./access.js";
import { attestationOf, checkAttestation, verifyChain } from "./attest.js";
import { GOOGLE_ATTESTATION_ROOTS } from "./attest-roots.js";
import { hex } from "./der.js";

export const CHALLENGE_TTL_MS = 120_000;
export const TOKEN_TTL_SECONDS = 30 * 86_400;
const MAX_BODY_BYTES = 24_000;
const MAX_CERT_BYTES = 4_096;

const json = (body, status = 200, headers = {}) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...headers } });

const fromB64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
let rootsCache;
const trustedRoots = () => (rootsCache ??= GOOGLE_ATTESTATION_ROOTS.map(fromB64));

/** challenge = случайные 16 байт ‖ срок (4 байта, секунды Unix) ‖ первые 16 байт HMAC: без хранения состояния на сервере. */
export async function newChallenge(secret, now = Date.now()) {
  const body = new Uint8Array(20);
  crypto.getRandomValues(body.subarray(0, 16));
  new DataView(body.buffer).setUint32(16, Math.floor((now + CHALLENGE_TTL_MS) / 1000));
  const mac = (await macOf(secret, Uint8Array.from([...new TextEncoder().encode("challenge:"), ...body]))).subarray(0, 16);
  const out = new Uint8Array(36);
  out.set(body);
  out.set(mac, 20);
  return out;
}

/** Байты задания, если подпись верна и срок не вышел; иначе null. */
export async function verifyChallenge(secret, b64, now = Date.now()) {
  let bytes;
  try {
    bytes = unb64url(String(b64));
  } catch {
    return null;
  }
  if (bytes.length !== 36) return null;
  const mac = (await macOf(secret, Uint8Array.from([...new TextEncoder().encode("challenge:"), ...bytes.subarray(0, 20)]))).subarray(0, 16);
  if (!mac.every((v, i) => v === bytes[20 + i])) return null;
  if (new DataView(bytes.buffer, bytes.byteOffset).getUint32(16) * 1000 < now) return null;
  return bytes;
}

async function throttled(env, request, bucket) {
  if (!env.LIMITER) return false;
  const { success } = await env.LIMITER.limit({ key: `${bucket}:${request.headers.get("cf-connecting-ip") ?? "anon"}` });
  return !success;
}

/** `deps.verifyChallenge` подменяется в тестах (настоящую цепочку снимали с фиксированным заданием). */
export async function handleAuth(request, env, now = Date.now(), deps = {}) {
  const url = new URL(request.url);
  const secret = env.APP_TOKEN_SECRET;
  if (!secret) return json({ error: "auth_unavailable" }, 503);
  if (await throttled(env, request, "auth")) return json({ error: "rate_limited", scope: "ip" }, 429, { "retry-after": "60" });

  if (url.pathname === "/v1/auth/challenge") {
    if (request.method !== "GET") return json({ error: "method_not_allowed" }, 405);
    return json({ challenge: b64url(await newChallenge(secret, now)), expires_in: CHALLENGE_TTL_MS / 1000 });
  }
  if (url.pathname !== "/v1/auth/register") return json({ error: "not_found" }, 404);
  if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);

  const text = await request.text();
  if (text.length > MAX_BODY_BYTES) return json({ error: "bad_request", reason: "too_large" }, 400);
  let body;
  try {
    body = JSON.parse(text);
  } catch {
    return json({ error: "bad_request", reason: "not_json" }, 400);
  }
  const challenge = await (deps.verifyChallenge ?? verifyChallenge)(secret, body?.challenge, now);
  if (!challenge) return json({ error: "bad_challenge" }, 400);

  let chain;
  try {
    if (!Array.isArray(body.chain)) throw new Error("chain");
    chain = body.chain.map((c) => fromB64(String(c)));
    if (chain.some((c) => c.length > MAX_CERT_BYTES)) throw new Error("size");
  } catch {
    return json({ error: "bad_request", reason: "chain" }, 400);
  }

  const fail = (reason) => json({ error: "attestation_failed", reason }, 403);
  const verified = await verifyChain(chain, trustedRoots(), now);
  if (!verified.ok) return fail(verified.error);
  const info = attestationOf(verified.certs);
  if (!info.ok) return fail(info.error);

  const { results: allowed } = await env.DB.prepare("SELECT package, sha256 FROM app_signatures WHERE enabled = 1").all();
  const rejectBootStates = (env.ATTEST_REJECT_BOOT_STATES ?? "failed").split(",").map((s) => s.trim()).filter(Boolean);
  const verdict = checkAttestation(info, { challenge, allowed, rejectBootStates });
  if (!verdict.ok) return fail(verdict.error);

  const installId = hex(new Uint8Array(await crypto.subtle.digest("SHA-256", info.leafSpki))).slice(0, 32);
  const known = await env.DB.prepare("SELECT status FROM installs WHERE id = ?1").bind(installId).first();
  if (known?.status === "revoked") return json({ error: "install_revoked" }, 403);
  await env.DB
    .prepare(
      "INSERT INTO installs (id, package, signature, security_level, boot_state, os_patch, first_seen, last_seen) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?7) " +
        "ON CONFLICT(id) DO UPDATE SET package = ?2, signature = ?3, security_level = ?4, boot_state = ?5, os_patch = ?6, last_seen = ?7, " +
        "tokens_issued = tokens_issued + 1",
    )
    .bind(installId, verdict.package, verdict.signature, info.securityLevel, info.bootState, info.osPatchLevel, now)
    .run();

  const token = await issueAppToken(secret, installId, TOKEN_TTL_SECONDS, now);
  return json({ token, expires_at: now + TOKEN_TTL_SECONDS * 1000, install_id: installId });
}
