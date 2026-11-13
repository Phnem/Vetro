// Выдать ключ вручную (пока нет бота и панели): печатает ключ ОДИН РАЗ и SQL, который кладёт в базу только его хэш.
//
//   node scripts/new-key.mjs <basic|pro|ultra> [--label site] [--owner site] [--origins vetrostudio.bid,*.vetrostudio.bid] [--days 365]
//   npx wrangler d1 execute vetro-books --remote --command "<напечатанный INSERT>"
//
// Ключ для страниц сайта (виден в браузере, поэтому обязательно --origins): пара `--origins vetrostudio.bid,phnem.github.io`.
import { newApiKey } from "../src/access.js";

const args = process.argv.slice(2);
const tier = args[0];
if (!["basic", "pro", "ultra"].includes(tier)) {
  console.error("usage: node scripts/new-key.mjs <basic|pro|ultra> [--label x] [--owner x] [--origins a,b] [--days n]");
  process.exit(1);
}
const opt = (name) => {
  const i = args.indexOf("--" + name);
  return i > 0 ? args[i + 1] : null;
};
const q = (v) => (v == null ? "NULL" : "'" + String(v).replace(/'/g, "''") + "'");

const key = await newApiKey(tier);
const origins = opt("origins")?.split(",").map((s) => s.trim()).filter(Boolean);
const days = opt("days");
const now = Date.now();
const sql =
  "INSERT INTO api_keys (id, key_hash, key_prefix, tier, status, label, owner, allowed_origins, created_at, expires_at) VALUES (" +
  [q(key.id), q(key.hash), q(key.prefix), q(tier), q("active"), q(opt("label")), q(opt("owner")),
    q(origins?.length ? JSON.stringify(origins) : null), now, days ? now + Number(days) * 86_400_000 : "NULL"].join(", ") + ");";

console.log("KEY (показан один раз, сохраните): " + key.plain);
console.log("ID: " + key.id + (origins?.length ? "   origins: " + origins.join(", ") : "   origins: любые"));
console.log("\nSQL:\n" + sql);
