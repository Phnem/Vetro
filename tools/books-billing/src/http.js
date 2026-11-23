// HTTP-слой: строгий CORS, единые заголовки, ApiError. Ни ключа сайта, ни авторизации на этих путях нет.

/** Сайты, которым разрешено ходить в оплату из браузера. Любой другой Origin ответа с CORS-заголовком не получает. */
export const ALLOWED_ORIGINS = new Set(["https://vetrostudio.bid", "https://www.vetrostudio.bid", "https://phnem.github.io"]);

/** Адрес Worker (страница тестовой оплаты) и адрес возврата после оплаты. Константы: клиент их менять не может, иначе открытый редирект. */
export const BILLING_BASE = "https://billing.vetrostudio.bid";
export const RETURN_BASE = "https://vetrostudio.bid/api.html";
export const returnUrl = (orderRef) => `${RETURN_BASE}?order=${orderRef}`;

export class ApiError extends Error {
  constructor(status, code, extra = {}) {
    super(code);
    this.status = status;
    this.code = code;
    this.extra = extra;
  }
}

const BASE = {
  "cache-control": "no-store",
  "referrer-policy": "no-referrer",
  "x-content-type-options": "nosniff",
  vary: "Origin",
};

export function corsHeaders(request) {
  const origin = request.headers.get("origin");
  if (!origin || !ALLOWED_ORIGINS.has(origin)) return {};
  return {
    "access-control-allow-origin": origin,
    "access-control-allow-methods": "POST, GET, OPTIONS",
    "access-control-allow-headers": "content-type",
    "access-control-max-age": "86400",
  };
}

export function json(request, data, status = 200, extra = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", ...BASE, ...corsHeaders(request), ...extra },
  });
}

export function preflight(request) {
  return new Response(null, { status: 204, headers: { ...BASE, ...corsHeaders(request) } });
}

export function html(request, body, status = 200) {
  return new Response(body, {
    status,
    headers: {
      "content-type": "text/html; charset=utf-8",
      "content-security-policy":
        "default-src 'none'; style-src 'unsafe-inline'; form-action 'self' https://vetrostudio.bid; base-uri 'none'; frame-ancestors 'none'",
      ...BASE,
      ...corsHeaders(request),
    },
  });
}

export function redirect(location) {
  return new Response(null, { status: 303, headers: { location, ...BASE } });
}

/** Читает тело целиком, но не больше `limit` байт: иначе 413. Content-Length проверяется заранее, фактический размер — после чтения. */
export async function readBody(request, limit) {
  const declared = Number(request.headers.get("content-length"));
  if (Number.isFinite(declared) && declared > limit) throw new ApiError(413, "too_large");
  const text = await request.text();
  if (new TextEncoder().encode(text).length > limit) throw new ApiError(413, "too_large");
  return text;
}

/** Ограничитель по IP. Привязка не хранит историю, адрес используется только как ключ счётчика и нигде не пишется. */
export async function limited(binding, prefix, request) {
  if (!binding || typeof binding.limit !== "function") return false;
  const ip = request.headers.get("cf-connecting-ip") ?? "anon";
  const { success } = await binding.limit({ key: `${prefix}:${ip}` });
  return !success;
}

export const isTest = (env) => env.BILLING_MODE === "test";
