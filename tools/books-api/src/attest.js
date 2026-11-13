// Проверка аттестации ключа Android (docs/adr/0003-api-access-keys-and-tiers.md, фаза 2).
//
// Приложение просит у телефона ключ с `setAttestationChallenge(challenge)`; телефон выдаёт цепочку сертификатов до корня Google, а в листе
// (расширение 1.3.6.1.4.1.11129.2.1.17) записаны имя пакета, SHA-256 подписи APK, уровень защиты и состояние загрузчика.
// Здесь три независимые части: проверка подписей цепочки (verifyChain), разбор расширения (parseKeyDescription) и политика (checkAttestation).
import { bytesOf, children, ecdsaDerToRaw, equalBytes, hex, intOf, oidOf, rawOf, readTLV, timeOf } from "./der.js";

export const OID_KEY_DESCRIPTION = "1.3.6.1.4.1.11129.2.1.17";
const OID_BASIC_CONSTRAINTS = "2.5.29.19";
const OID_KEY_USAGE = "2.5.29.15";
const OID_EC = "1.2.840.10045.2.1";
const OID_RSA = "1.2.840.113549.1.1.1";
const CURVES = { "1.2.840.10045.3.1.7": { name: "P-256", size: 32 }, "1.3.132.0.34": { name: "P-384", size: 48 } };
const SIG_ALGS = {
  "1.2.840.10045.4.3.2": { kind: "ec", hash: "SHA-256" },
  "1.2.840.10045.4.3.3": { kind: "ec", hash: "SHA-384" },
  "1.2.840.113549.1.1.11": { kind: "rsa", hash: "SHA-256" },
  "1.2.840.113549.1.1.12": { kind: "rsa", hash: "SHA-384" },
};
const LEVELS = ["software", "tee", "strongbox"];
const BOOT_STATES = ["verified", "self_signed", "unverified", "failed"];
const CLOCK_SKEW_MS = 24 * 3600_000;

/** Сертификат X.509 → поля, нужные для проверки (DER-срезы без копирования). */
export function parseCertificate(der) {
  const cert = readTLV(der, 0);
  const [tbs, sigAlg, sigBits] = children(der, cert);
  const kids = children(der, tbs);
  let i = 0;
  if (kids[0].cls === 2 && kids[0].tag === 0) i++; // [0] version
  const serial = kids[i++];
  i++; // signature (дубль algorithm)
  const issuer = kids[i++];
  const validity = kids[i++];
  const subject = kids[i++];
  const spki = kids[i++];
  const extensions = [];
  for (; i < kids.length; i++) {
    if (kids[i].cls === 2 && kids[i].tag === 3) {
      for (const e of children(der, children(der, kids[i])[0])) {
        const k = children(der, e);
        extensions.push({ oid: oidOf(der, k[0]), value: bytesOf(der, k[k.length - 1]) });
      }
    }
  }
  const [nb, na] = children(der, validity);
  // basicConstraints (cA) и keyUsage (keyCertSign): без них нельзя отличить настоящего издателя от ключа приложения, умеющего подписывать
  const bc = extensions.find((e) => e.oid === OID_BASIC_CONSTRAINTS);
  const ku = extensions.find((e) => e.oid === OID_KEY_USAGE);
  let isCA = false;
  if (bc) {
    const [first] = children(bc.value, readTLV(bc.value, 0));
    isCA = !!first && first.tag === 1 && bc.value[first.start] !== 0; // BOOLEAN cA
  }
  let keyCertSign = null; // null: расширения нет, ограничений по ключу нет
  if (ku) {
    const bits = bytesOf(ku.value, readTLV(ku.value, 0)); // [число неиспользуемых бит, байты флагов…]
    keyCertSign = bits.length > 1 && (bits[1] & 0x04) !== 0;
  }
  return {
    isCA,
    keyCertSign,
    tbs: rawOf(der, tbs),
    sigAlgOid: oidOf(der, children(der, sigAlg)[0]),
    signature: bytesOf(der, sigBits).subarray(1), // первый байт BIT STRING — число неиспользуемых бит
    issuer: rawOf(der, issuer),
    subject: rawOf(der, subject),
    spki: rawOf(der, spki),
    notBefore: timeOf(der, nb),
    notAfter: timeOf(der, na),
    serial: hex(bytesOf(der, serial)),
    extensions,
  };
}

function parseSpki(spki) {
  const seq = readTLV(spki, 0);
  const [alg] = children(spki, seq);
  const [algOid, params] = children(spki, alg);
  const oid = oidOf(spki, algOid);
  if (oid === OID_RSA) return { type: "rsa" };
  if (oid === OID_EC && params) {
    const curve = CURVES[oidOf(spki, params)];
    if (curve) return { type: "ec", curve };
  }
  return { type: "unknown" };
}

/** Подпись `cert` проверена открытым ключом издателя (его SPKI). */
export async function verifySignature(cert, issuerSpki) {
  const alg = SIG_ALGS[cert.sigAlgOid];
  const key = parseSpki(issuerSpki);
  if (!alg) return false;
  try {
    if (alg.kind === "ec" && key.type === "ec") {
      const k = await crypto.subtle.importKey("spki", issuerSpki, { name: "ECDSA", namedCurve: key.curve.name }, false, ["verify"]);
      return await crypto.subtle.verify({ name: "ECDSA", hash: alg.hash }, k, ecdsaDerToRaw(cert.signature, key.curve.size), cert.tbs);
    }
    if (alg.kind === "rsa" && key.type === "rsa") {
      const k = await crypto.subtle.importKey("spki", issuerSpki, { name: "RSASSA-PKCS1-v1_5", hash: alg.hash }, false, ["verify"]);
      return await crypto.subtle.verify("RSASSA-PKCS1-v1_5", k, cert.signature, cert.tbs);
    }
  } catch {
    return false;
  }
  return false;
}

/**
 * Цепочка (DER, лист первым): каждая подпись проверена ключом следующего, имена издателей сходятся, срок действия в порядке, а последний
 * сертификат — корень из `roots` (SPKI в DER, Uint8Array). → {ok: true, certs} | {ok: false, error}.
 */
export async function verifyChain(chain, roots, now = Date.now()) {
  if (!Array.isArray(chain) || chain.length < 2 || chain.length > 8) return { ok: false, error: "bad_chain" };
  let certs;
  try {
    certs = chain.map(parseCertificate);
  } catch {
    return { ok: false, error: "bad_certificate" };
  }
  // Структура до подписей. Расширение аттестации бывает ТОЛЬКО у листа: иначе злоумышленник подписал бы своим аттестованным ключом
  // поддельный «лист» с чужим именем пакета и поставил его перед настоящей цепочкой своего телефона. Все сертификаты выше листа
  // обязаны быть центрами сертификации (cA, keyCertSign), а ключ приложения таким не является.
  for (let i = 0; i < certs.length; i++) {
    if (i === 0) continue;
    if (certs[i].extensions.some((e) => e.oid === OID_KEY_DESCRIPTION)) return { ok: false, error: "misplaced_attestation", index: i };
    if (!certs[i].isCA || certs[i].keyCertSign === false) return { ok: false, error: "not_ca", index: i };
  }
  for (let i = 0; i < certs.length; i++) {
    const c = certs[i];
    if (now + CLOCK_SKEW_MS < c.notBefore || now - CLOCK_SKEW_MS > c.notAfter) return { ok: false, error: "certificate_expired", index: i };
    if (i + 1 < certs.length) {
      const up = certs[i + 1];
      if (!equalBytes(c.issuer, up.subject)) return { ok: false, error: "broken_chain", index: i };
      if (!(await verifySignature(c, up.spki))) return { ok: false, error: "bad_signature", index: i };
    }
  }
  if (!roots.some((r) => equalBytes(r, certs[certs.length - 1].spki))) return { ok: false, error: "untrusted_root" };
  return { ok: true, certs };
}

/** Расширение KeyDescription листа → разобранные поля. Бросает исключение на чужом формате. */
export function parseKeyDescription(buf) {
  const k = children(buf, readTLV(buf, 0));
  const level = (t) => LEVELS[intOf(buf, t)] ?? "unknown";
  const info = {
    version: intOf(buf, k[0]),
    securityLevel: level(k[1]),
    keymasterLevel: level(k[3]),
    challenge: bytesOf(buf, k[4]).slice(),
    packages: [],
    signatureDigests: [],
    bootState: null,
    deviceLocked: null,
    osPatchLevel: null,
  };
  for (const list of [k[6], k[7]]) {
    for (const f of children(buf, list)) {
      if (f.cls !== 2) continue;
      const inner = children(buf, f)[0];
      if (!inner) continue;
      if (f.tag === 704) {
        const r = children(buf, inner); // verifiedBootKey, deviceLocked, verifiedBootState, verifiedBootHash
        info.deviceLocked = bytesOf(buf, r[1])[0] !== 0;
        info.bootState = BOOT_STATES[intOf(buf, r[2])] ?? "unknown";
      } else if (f.tag === 706) {
        info.osPatchLevel = intOf(buf, inner);
      } else if (f.tag === 709) {
        const app = bytesOf(buf, inner); // OCTET STRING с DER AttestationApplicationId
        const [packages, digests] = children(app, readTLV(app, 0));
        for (const p of children(app, packages)) {
          const [name, version] = children(app, p);
          info.packages.push({ name: new TextDecoder().decode(bytesOf(app, name)), version: intOf(app, version) });
        }
        for (const d of children(app, digests)) info.signatureDigests.push(hex(bytesOf(app, d)));
      }
    }
  }
  return info;
}

/** Атрибуты листа: разобранное расширение и SPKI его ключа (из него выводится постоянный id установки). */
export function attestationOf(certs) {
  const ext = certs[0].extensions.find((e) => e.oid === OID_KEY_DESCRIPTION);
  if (!ext) return { ok: false, error: "no_attestation_extension" };
  try {
    return { ok: true, ...parseKeyDescription(ext.value), leafSpki: certs[0].spki };
  } catch {
    return { ok: false, error: "bad_attestation_extension" };
  }
}

/**
 * Политика. `allowed`: [{package, sha256}] (включённые строки app_signatures). Требуется: тот же challenge, защита TEE или StrongBox,
 * пакет и ВСЕ подписи из списка, загрузчик не в состоянии `failed`. → {ok: true} | {ok: false, error}.
 */
export function checkAttestation(info, { challenge, allowed, rejectBootStates = ["failed"] }) {
  if (!equalBytes(info.challenge, challenge)) return { ok: false, error: "challenge_mismatch" };
  if (info.securityLevel === "software" || info.keymasterLevel === "software" || info.securityLevel === "unknown") {
    return { ok: false, error: "software_attestation" };
  }
  if (rejectBootStates.includes(info.bootState)) return { ok: false, error: "boot_state_rejected" };
  if (!info.packages.length || !info.signatureDigests.length) return { ok: false, error: "no_app_identity" };
  const pkg = info.packages.find((p) => allowed.some((a) => a.package === p.name));
  if (!pkg) return { ok: false, error: "package_not_allowed" };
  const known = new Set(allowed.filter((a) => a.package === pkg.name).map((a) => a.sha256.toLowerCase()));
  if (!info.signatureDigests.every((d) => known.has(d))) return { ok: false, error: "signature_not_allowed" };
  return { ok: true, package: pkg.name, signature: info.signatureDigests[0] };
}
