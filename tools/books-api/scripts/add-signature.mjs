// Разрешить подпись APK для токена установки: печатает SQL для таблицы app_signatures.
//
//   node scripts/add-signature.mjs <package> <sha256 сертификата подписи> [метка]
//   npx wrangler d1 execute vetro-books --remote --command "<напечатанный INSERT>"
//
// SHA-256 подписи APK: `apksigner verify --print-certs app.apk` (строка "certificate SHA-256 digest") или
// `keytool -printcert -jarfile app.apk`. Метки: release | fdroid | debug-perf. Отключить подпись: UPDATE app_signatures SET enabled = 0 WHERE sha256 = '…'.
const [pkg, sha, label] = process.argv.slice(2);
const digest = (sha ?? "").replace(/[:\s]/g, "").toLowerCase();
if (!pkg || !/^[0-9a-f]{64}$/.test(digest) || !/^[A-Za-z0-9_.]+$/.test(pkg)) {
  console.error("usage: node scripts/add-signature.mjs <package> <sha256 hex (64)> [label]");
  process.exit(1);
}
const q = (v) => (v == null ? "NULL" : "'" + String(v).replace(/'/g, "''") + "'");
console.log(`INSERT OR REPLACE INTO app_signatures (package, sha256, label, enabled) VALUES (${q(pkg)}, ${q(digest)}, ${q(label)}, 1);`);
