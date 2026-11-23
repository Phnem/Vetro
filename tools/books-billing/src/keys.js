// Криптография и генерация ключей: скопировано из tools/books-api/src/access.js (через tools/books-bot/src/keys.js).
// Формат vk_<tier>_<32 символа [a-z2-7]>. Парити-тест: test/keys.test.js.
// Открытый ключ нигде не сохраняется и не логируется (кроме одноразового REVEAL и письма): в D1 идут только SHA-256 и префикс.

const te = new TextEncoder();

export async function sha256Hex(text) {
  const d = await crypto.subtle.digest("SHA-256", te.encode(text));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

const BASE32 = "abcdefghijklmnopqrstuvwxyz234567";

/** Новый ключ: показать `plain` покупателю один раз, в базу идут `id`, `hash`, `prefix`. */
export async function newApiKey(tier) {
  const raw = crypto.getRandomValues(new Uint8Array(32));
  const secret = [...raw].map((b) => BASE32[b & 31]).join("");
  const plain = `vk_${tier}_${secret}`;
  const id = "k_" + [...crypto.getRandomValues(new Uint8Array(6))].map((b) => b.toString(16).padStart(2, "0")).join("");
  return { id, plain, hash: await sha256Hex(plain), prefix: plain.slice(0, 12) + "…" };
}

/** Идентификатор заказа для сайта: «ord_» + 32 hex (128 бит). Он же ключ доступа к одноразовому показу ключа. */
export function newOrderRef() {
  return "ord_" + [...crypto.getRandomValues(new Uint8Array(16))].map((b) => b.toString(16).padStart(2, "0")).join("");
}
