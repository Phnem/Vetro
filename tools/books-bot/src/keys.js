// Криптография и генерация ключей: скопировано из tools/books-api/src/access.js.
// Сам открытый ключ нигде не сохраняется и не логируется; в D1 кладётся только SHA-256 и префикс.

const te = new TextEncoder();

export async function sha256Hex(text) {
  const d = await crypto.subtle.digest("SHA-256", te.encode(text));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

const BASE32 = "abcdefghijklmnopqrstuvwxyz234567";

/** Новый ключ стороннего клиента: показать `plain` владельцу один раз, в базу идут `id`, `hash`, `prefix`. */
export async function newApiKey(tier) {
  const raw = crypto.getRandomValues(new Uint8Array(32));
  const secret = [...raw].map((b) => BASE32[b & 31]).join("");
  const plain = `vk_${tier}_${secret}`;
  const id = "k_" + [...crypto.getRandomValues(new Uint8Array(6))].map((b) => b.toString(16).padStart(2, "0")).join("");
  return { id, plain, hash: await sha256Hex(plain), prefix: plain.slice(0, 12) + "…" };
}

/** Случайный payload для sendInvoice (используется для поиска заказа в pre_checkout_query / successful_payment). */
export function newInvoicePayload() {
  return "ord_" + [...crypto.getRandomValues(new Uint8Array(12))].map((b) => b.toString(16).padStart(2, "0")).join("");
}
