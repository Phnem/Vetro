// Список отзыва аттестационных ключей Google (https://developer.android.com/privacy-and-security/security-key-attestation#certificate_status).
// В нём серийные номера сертификатов, которые отозваны (ключ утёк, устройство взломано) или приостановлены. Если в цепочке телефона есть такой
// серийный номер, токен не выдаётся. Список недоступен или не разобрался — пропускаем: из-за сбоя у Google никто не остаётся без каталога.
export const DEFAULT_STATUS_URL = "https://android.googleapis.com/attestation/status";
const CACHE_SECONDS = 6 * 3600;
const BAD_STATUSES = new Set(["REVOKED", "SUSPENDED"]);

/** Google пишет серийные номера строчными шестнадцатеричными без ведущих нулей; в сертификате перед старшим байтом бывает 00. */
export const normalizeSerial = (hex) => String(hex).toLowerCase().replace(/^0+/, "") || "0";

/** → {serial: {status, reason}} или null, если списка нет. Кэш на шесть часов в кэше края. */
export async function loadRevocations(env) {
  const url = env.ATTEST_STATUS_URL === undefined ? DEFAULT_STATUS_URL : env.ATTEST_STATUS_URL;
  if (!url) return null; // отключено (тесты, локальная отладка)
  const key = new Request(url);
  try {
    const hit = await caches.default.match(key);
    if (hit) return (await hit.json()).entries ?? null;
    const res = await fetch(url, { headers: { "cache-control": "no-cache" } });
    if (!res.ok) return null;
    const text = await res.text();
    const entries = JSON.parse(text).entries;
    if (!entries || typeof entries !== "object") return null;
    await caches.default.put(key, new Response(text, { headers: { "content-type": "application/json", "cache-control": `public, max-age=${CACHE_SECONDS}` } }));
    return entries;
  } catch {
    return null;
  }
}

/** Первый сертификат цепочки с отозванным или приостановленным серийным номером → {serial, status} | null. */
export function revokedInChain(certs, entries) {
  if (!entries) return null;
  for (const c of certs) {
    const e = entries[normalizeSerial(c.serial)];
    if (e && BAD_STATUSES.has(String(e.status).toUpperCase())) return { serial: normalizeSerial(c.serial), status: e.status };
  }
  return null;
}
