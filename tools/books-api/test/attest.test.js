import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { attestationOf, checkAttestation, parseCertificate, verifyChain } from "../src/attest.js";
import { GOOGLE_ATTESTATION_ROOTS } from "../src/attest-roots.js";
import { ecdsaDerToRaw, hex, readTLV } from "../src/der.js";

// Настоящая цепочка, снятая KeyAttestation.chain() на Xiaomi 13 (Android 16): 5 сертификатов, корень Google.
const fx = JSON.parse(readFileSync(new URL("./fixtures/attestation-xiaomi13.json", import.meta.url), "utf8"));
const b64 = (s) => new Uint8Array(Buffer.from(s, "base64"));
const chain = () => fx.chain.map((c) => b64(c));
const roots = GOOGLE_ATTESTATION_ROOTS.map(b64);
const NOW = fx.captured_at_ms;
const challenge = b64(fx.challenge);
const SIG = "74444e41d79317d71eff7fd77367603c70fb5e365cd75c7e849170e8c5f2962a"; // подпись debug-ключа, которой подписан .perf
const allowed = [{ package: "com.phnem.vetro.perf", sha256: SIG }];

async function verified() {
  const res = await verifyChain(chain(), roots, NOW);
  assert.equal(res.ok, true, JSON.stringify(res));
  return res.certs;
}

test("DER: long-form tags and lengths, ECDSA signature conversion", () => {
  // [704] EXPLICIT: BF 85 40 — тег в длинной форме, как в расширении аттестации
  const tlv = readTLV(Uint8Array.from([0xbf, 0x85, 0x40, 0x03, 0x02, 0x01, 0x01]), 0);
  assert.deepEqual([tlv.cls, tlv.constructed, tlv.tag, tlv.end - tlv.start], [2, true, 704, 3]);
  assert.throws(() => readTLV(Uint8Array.from([0x30, 0x05, 0x01]), 0), /exceeds/);
  // SEQUENCE{INTEGER 0x00ff, INTEGER 0x01}: ведущий ноль срезается, значения выравниваются вправо до 4 байт
  const raw = ecdsaDerToRaw(Uint8Array.from([0x30, 0x07, 0x02, 0x02, 0x00, 0xff, 0x02, 0x01, 0x01]), 4);
  assert.equal(hex(raw), "000000ff" + "00000001");
});

test("the real Xiaomi 13 chain verifies up to the Google root", async () => {
  const certs = await verified();
  assert.equal(certs.length, 5);
  const leaf = parseCertificate(chain()[0]);
  assert.ok(leaf.extensions.some((e) => e.oid === "1.3.6.1.4.1.11129.2.1.17"));
});

test("the attestation extension says what the phone knows about the app", async () => {
  const info = attestationOf(await verified());
  assert.equal(info.ok, true);
  assert.equal(info.securityLevel, "tee");
  assert.equal(info.keymasterLevel, "tee");
  assert.deepEqual(info.packages.map((p) => p.name), ["com.phnem.vetro.perf"]);
  assert.deepEqual(info.signatureDigests, [SIG]);
  assert.deepEqual(Buffer.from(info.challenge), Buffer.from(challenge));
  assert.equal(info.bootState, "unverified"); // загрузчик телефона разблокирован: политика это допускает, но запоминает
  assert.equal(info.deviceLocked, false);
  assert.ok(info.osPatchLevel >= 202601);
});

test("policy accepts the real attestation for an allowed package and signature", async () => {
  const info = attestationOf(await verified());
  assert.deepEqual(checkAttestation(info, { challenge, allowed }), { ok: true, package: "com.phnem.vetro.perf", signature: SIG });
});

test("policy rejects: other challenge, other package, other signature, software level, failed boot", async () => {
  const info = attestationOf(await verified());
  const run = (patch, opts = {}) => checkAttestation({ ...info, ...patch }, { challenge, allowed, ...opts }).error;
  assert.equal(checkAttestation(info, { challenge: new Uint8Array(32), allowed }).error, "challenge_mismatch");
  assert.equal(checkAttestation(info, { challenge, allowed: [{ package: "com.other.app", sha256: SIG }] }).error, "package_not_allowed");
  assert.equal(checkAttestation(info, { challenge, allowed: [{ package: "com.phnem.vetro.perf", sha256: "ab".repeat(32) }] }).error, "signature_not_allowed");
  assert.equal(checkAttestation(info, { challenge, allowed: [] }).error, "package_not_allowed");
  assert.equal(run({ securityLevel: "software" }), "software_attestation");
  assert.equal(run({ keymasterLevel: "software" }), "software_attestation");
  assert.equal(run({ bootState: "failed" }), "boot_state_rejected");
  assert.equal(run({ packages: [], signatureDigests: [] }), "no_app_identity");
  // лишняя неизвестная подпись рядом с известной: отказ (подпись должна быть вся из списка)
  assert.equal(run({ signatureDigests: [SIG, "cd".repeat(32)] }), "signature_not_allowed");
  // политика может ужесточиться: не пускать и разблокированный загрузчик
  assert.equal(run({}, { rejectBootStates: ["failed", "unverified"] }), "boot_state_rejected");
});

test("verifyChain rejects a foreign root, a cut chain, a swapped order and an expired chain", async () => {
  assert.equal((await verifyChain(chain(), [], NOW)).error, "untrusted_root");
  assert.equal((await verifyChain(chain(), [new Uint8Array(91)], NOW)).error, "untrusted_root");
  assert.equal((await verifyChain(chain().slice(0, 4), roots, NOW)).error, "untrusted_root"); // корня нет: последний — промежуточный
  assert.equal((await verifyChain(chain().slice(0, 1), roots, NOW)).error, "bad_chain");
  const swapped = chain();
  [swapped[1], swapped[2]] = [swapped[2], swapped[1]];
  assert.equal((await verifyChain(swapped, roots, NOW)).error, "broken_chain");
  const later = NOW + 400 * 86_400_000; // промежуточный сертификат «Droid CA3» живёт две недели
  assert.equal((await verifyChain(chain(), roots, later)).error, "certificate_expired");
  assert.equal((await verifyChain(["not", "certs"], roots, NOW)).error, "bad_certificate");
});

test("verifyChain rejects a tampered certificate", async () => {
  const flipped = (index, offsetFromEnd) => {
    const c = chain();
    c[index][c[index].length - offsetFromEnd] ^= 0xff;
    return c;
  };
  assert.equal((await verifyChain(flipped(0, 10), roots, NOW)).error, "bad_signature"); // подпись листа
  const tbsTouched = chain();
  const leaf = parseCertificate(tbsTouched[0]);
  const at = Buffer.from(tbsTouched[0]).indexOf(Buffer.from(leaf.spki)) + 40; // байт внутри открытого ключа листа
  tbsTouched[0][at] ^= 0x01;
  assert.equal((await verifyChain(tbsTouched, roots, NOW)).error, "bad_signature");
});
