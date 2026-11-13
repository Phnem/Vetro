// Панель владельца API каталога (dash.vetrostudio.bid). Порядок: токен Cloudflare Access -> защита от CSRF -> страница или JSON API.
import { handleApi } from "./api.js";
import { verifyAccessJwt } from "./jwt.js";
import { APP_CSS, APP_JS, INDEX_HTML } from "./page.js";

const SECURITY = {
  "content-security-policy": "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
  "x-content-type-options": "nosniff",
  "referrer-policy": "no-referrer",
  "cache-control": "no-store",
};

const text = (body, type, status = 200) => new Response(body, { status, headers: { "content-type": type, ...SECURITY } });
const json = (data, status = 200) => text(JSON.stringify(data), "application/json; charset=utf-8", status);

export default {
  async fetch(request, env, ctx, deps = {}) {
    const team = (env.ACCESS_TEAM_DOMAIN ?? "").trim();
    const aud = (env.ACCESS_AUD ?? "").trim();
    // Закрыто по умолчанию: пока Access не настроен, не отвечаем ничем полезным.
    if (!team || !aud) return json({ error: "access_not_configured" }, 503);

    const auth = await verifyAccessJwt(request.headers.get("cf-access-jwt-assertion"), { team, aud, owner: env.OWNER_EMAIL }, deps);
    if (!auth.ok) return json({ error: "unauthorized" }, 401);

    const url = new URL(request.url);
    const method = request.method;
    if (method === "GET" || method === "HEAD") {
      if (url.pathname === "/" || url.pathname === "/index.html") return text(INDEX_HTML, "text/html; charset=utf-8");
      if (url.pathname === "/app.js") return text(APP_JS, "text/javascript; charset=utf-8");
      if (url.pathname === "/app.css") return text(APP_CSS, "text/css; charset=utf-8");
    } else {
      // Изменения только со страницы панели: нестандартный заголовок нельзя отправить с чужого сайта без preflight, а Origin должен быть нашим.
      const origin = request.headers.get("origin");
      if (request.headers.get("x-dash") !== "1" || (origin && origin !== url.origin)) return json({ error: "csrf" }, 403);
    }
    if (!url.pathname.startsWith("/api/")) return json({ error: "not_found" }, 404);

    const [status, data] = await handleApi(request, env, auth.email, deps.now);
    return json(data, status);
  },
};
