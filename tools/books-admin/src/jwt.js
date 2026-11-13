// Проверка токена Cloudflare Access (заголовок Cf-Access-Jwt-Assertion): RS256, ключи из JWKS команды, aud и срок.
// Access стоит перед воркером, но запрос в обход (прямой адрес воркера) токена не имеет: проверяем сами, закрыто по умолчанию.
const dec = (s) => Uint8Array.from(atob(s.replace(/-/g, "+").replace(/_/g, "/")), (c) => c.charCodeAt(0));
const parse = (s) => JSON.parse(new TextDecoder().decode(dec(s)));

let jwksCache = { at: 0, team: "", keys: [] };

async function loadKeys(team, fetchJwks, now, force) {
  if (!force && jwksCache.team === team && now - jwksCache.at < 3_600_000) return jwksCache.keys;
  const keys = (await fetchJwks(`https://${team}/cdn-cgi/access/certs`)).keys ?? [];
  jwksCache = { at: now, team, keys };
  return keys;
}

/** {ok:true, email} или {ok:false, reason}. `deps` нужен тестам: подставной JWKS и часы. */
export async function verifyAccessJwt(token, cfg, deps = {}) {
  const fetchJwks = deps.fetchJwks ?? (async (url) => (await fetch(url)).json());
  const now = deps.now ?? Date.now();
  const bad = (reason) => ({ ok: false, reason });
  try {
    const parts = String(token ?? "").split(".");
    if (parts.length !== 3) return bad("malformed");
    const header = parse(parts[0]);
    const claims = parse(parts[1]);
    if (header.alg !== "RS256") return bad("alg");
    let jwk = (await loadKeys(cfg.team, fetchJwks, now, false)).find((k) => k.kid === header.kid);
    if (!jwk) jwk = (await loadKeys(cfg.team, fetchJwks, now, true)).find((k) => k.kid === header.kid); // ключи сменились
    if (!jwk) return bad("kid");
    const key = await crypto.subtle.importKey("jwk", { kty: jwk.kty, n: jwk.n, e: jwk.e, alg: "RS256", ext: true }, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
    const okSig = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, dec(parts[2]), new TextEncoder().encode(parts[0] + "." + parts[1]));
    if (!okSig) return bad("signature");
    if (claims.iss !== `https://${cfg.team}`) return bad("iss");
    const aud = Array.isArray(claims.aud) ? claims.aud : [claims.aud];
    if (!aud.includes(cfg.aud)) return bad("aud");
    if (typeof claims.exp !== "number" || claims.exp * 1000 <= now) return bad("expired");
    if (typeof claims.nbf === "number" && claims.nbf * 1000 > now + 60_000) return bad("nbf");
    const email = String(claims.email ?? "").toLowerCase();
    if (!email) return bad("no_email");
    if (cfg.owner && email !== cfg.owner.toLowerCase()) return bad("not_owner");
    return { ok: true, email };
  } catch {
    return bad("error");
  }
}
