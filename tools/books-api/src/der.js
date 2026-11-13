// Минимальный разбор DER (ASN.1) для сертификатов X.509 и расширения аттестации ключа Android. Только чтение; без зависимостей,
// потому что в среде Workers нет готового разборщика. Покрыто тестами на настоящей цепочке с телефона (test/attest.test.js).

/**
 * Читает один TLV с позиции `off`. Теги больше 30 (контекстные [704], [709] в аттестации) используют длинную форму номера.
 * → {cls, constructed, tag, start, end, next}: start..end — содержимое, next — начало следующего элемента.
 */
export function readTLV(buf, off = 0) {
  if (off >= buf.length) throw new Error("der: unexpected end");
  const first = buf[off];
  let p = off + 1;
  let tag = first & 0x1f;
  if (tag === 0x1f) {
    tag = 0;
    let b;
    do {
      if (p >= buf.length) throw new Error("der: truncated tag");
      b = buf[p++];
      tag = tag * 128 + (b & 0x7f);
    } while (b & 0x80);
  }
  if (p >= buf.length) throw new Error("der: truncated length");
  let len = buf[p++];
  if (len & 0x80) {
    const n = len & 0x7f;
    if (n === 0 || n > 4) throw new Error("der: unsupported length form");
    len = 0;
    for (let i = 0; i < n; i++) {
      if (p >= buf.length) throw new Error("der: truncated length");
      len = len * 256 + buf[p++];
    }
  }
  const end = p + len;
  if (end > buf.length) throw new Error("der: element exceeds buffer");
  return { cls: first >> 6, constructed: !!(first & 0x20), tag, start: p, end, next: end, head: off };
}

/** Дети составного элемента. */
export function children(buf, tlv) {
  const out = [];
  for (let p = tlv.start; p < tlv.end; ) {
    const c = readTLV(buf, p);
    out.push(c);
    p = c.next;
  }
  return out;
}

export const bytesOf = (buf, tlv) => buf.subarray(tlv.start, tlv.end);
/** Элемент целиком вместе с заголовком: нужен для подписанной части сертификата и для сравнения имён. */
export const rawOf = (buf, tlv) => buf.subarray(tlv.head, tlv.end);

export function oidOf(buf, tlv) {
  const b = bytesOf(buf, tlv);
  if (!b.length) throw new Error("der: empty oid");
  const parts = [Math.floor(b[0] / 40), b[0] % 40];
  let v = 0;
  for (let i = 1; i < b.length; i++) {
    v = v * 128 + (b[i] & 0x7f);
    if (!(b[i] & 0x80)) {
      parts.push(v);
      v = 0;
    }
  }
  return parts.join(".");
}

/** INTEGER/ENUMERATED как число (до 2^53; для серийных номеров и версий хватает). */
export function intOf(buf, tlv) {
  const b = bytesOf(buf, tlv);
  let v = 0;
  for (const x of b) v = v * 256 + x;
  return v;
}

/** UTCTime (YYMMDDHHMMSSZ) и GeneralizedTime (YYYYMMDDHHMMSSZ) → мс Unix. */
export function timeOf(buf, tlv) {
  const s = new TextDecoder().decode(bytesOf(buf, tlv));
  const m = tlv.tag === 0x17 ? /^(\d\d)(\d\d)(\d\d)(\d\d)(\d\d)(\d\d)?Z$/.exec(s) : /^(\d{4})(\d\d)(\d\d)(\d\d)(\d\d)(\d\d)?Z$/.exec(s);
  if (!m) throw new Error("der: bad time " + s);
  let year = +m[1];
  if (tlv.tag === 0x17) year += year >= 50 ? 1900 : 2000;
  return Date.UTC(year, +m[2] - 1, +m[3], +m[4], +m[5], +(m[6] ?? 0));
}

export const hex = (bytes) => [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");

export const equalBytes = (a, b) => a.length === b.length && a.every((x, i) => x === b[i]);

/** ECDSA-подпись DER SEQUENCE{r,s} → «сырая» r||s фиксированной длины (то, что ждёт WebCrypto). */
export function ecdsaDerToRaw(sig, size) {
  const seq = readTLV(sig, 0);
  const [r, s] = children(sig, seq);
  const fit = (t) => {
    let b = bytesOf(sig, t);
    while (b.length > size && b[0] === 0) b = b.subarray(1);
    if (b.length > size) throw new Error("der: ecdsa integer too long");
    const out = new Uint8Array(size);
    out.set(b, size - b.length);
    return out;
  };
  const out = new Uint8Array(size * 2);
  out.set(fit(r), 0);
  out.set(fit(s), size);
  return out;
}
