// Серверная проверка тела checkout. Клиентская проверка на сайте — только удобство, эта — главная.
import { ApiError } from "./http.js";
import { sanitizeSignatureSvg, SvgError } from "./svg.js";

export const METHODS = ["card", "usdt", "ru"]; // ru зарезервирован под российские карты; включён ли метод, решает реестр провайдеров
export const PROJECT_TYPES = ["site", "app", "server", "other"];

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const PLAN_RE = /^[a-z]{2,16}$/;
const TERMS_RE = /^[0-9a-f]{12}$/;
// Как ORIGIN_RE панели (tools/books-admin/src/api.js), но без порта: на сайте домены вводятся без схемы, пути и порта.
const HOST_RE = /^(\*\.)?[a-z0-9]([a-z0-9.-]*[a-z0-9])?$/;
const MAX_DOMAINS = 20;

const bad = (field) => new ApiError(400, "bad_field", { field });
const isObj = (v) => v !== null && typeof v === "object" && !Array.isArray(v);
const len = (s) => [...s].length;
// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/;

function text(v, min, max, field, { multiline = false } = {}) {
  if (typeof v !== "string") throw bad(field);
  const s = v.trim();
  if (CONTROL.test(s) || (!multiline && /[\r\n\t]/.test(s))) throw bad(field);
  if (len(s) < min || len(s) > max) throw bad(field);
  return s;
}

export function cleanDomain(raw) {
  if (typeof raw !== "string") return null;
  const d = raw.trim().toLowerCase();
  if (!d || d.length > 253 || !HOST_RE.test(d) || d.includes("..")) return null;
  if (d.startsWith("*.") && !d.slice(2).includes(".")) return null; // «*.com» превратил бы ограничение в пустое
  return d;
}

function domains(v, type) {
  if (v === undefined || v === null) v = [];
  if (!Array.isArray(v) || v.length > MAX_DOMAINS) throw bad("domains");
  const out = [];
  for (const raw of v) {
    const d = cleanDomain(raw);
    if (d === null) {
      if (type === "site") throw bad("domains");
      continue; // у не-сайтов домены необязательны: невалидные отбрасываем, валидные сохраняем
    }
    if (!out.includes(d)) out.push(d);
  }
  if (type === "site" && out.length === 0) throw bad("domains");
  return out;
}

/** Возвращает очищенные значения или бросает ApiError(400, bad_field | bad_method | bad_signature). */
export function validateCheckout(body) {
  if (!isObj(body)) throw bad("body");
  const { plan, method, lang, email, project, contract } = body;

  if (typeof plan !== "string" || !PLAN_RE.test(plan)) throw bad("plan");
  if (typeof method !== "string" || !METHODS.includes(method)) throw new ApiError(400, "bad_method");
  if (lang !== "en" && lang !== "ru") throw bad("lang");

  if (typeof email !== "string") throw bad("email");
  const mail = email.trim().toLowerCase();
  if (mail.length > 254 || !EMAIL_RE.test(mail) || CONTROL.test(mail)) throw bad("email");

  if (!isObj(project)) throw bad("project");
  const name = text(project.name, 2, 80, "name");
  if (typeof project.type !== "string" || !PROJECT_TYPES.includes(project.type)) throw bad("type");
  const description = text(project.description, 10, 400, "description", { multiline: true });
  const hosts = domains(project.domains, project.type);

  if (!isObj(contract)) throw bad("contract");
  const signer = text(contract.signer, 2, 80, "signer");
  if (typeof contract.termsVersion !== "string" || !TERMS_RE.test(contract.termsVersion)) throw bad("termsVersion");
  // Время браузера не доверенное: сохраняем как отдельное поле, если оно читается как дата, иначе не сохраняем.
  let signedAt = null;
  if (typeof contract.signedAt === "string" && contract.signedAt.length <= 40 && Number.isFinite(Date.parse(contract.signedAt))) {
    signedAt = contract.signedAt;
  }
  let signatureSvg;
  try {
    signatureSvg = sanitizeSignatureSvg(contract.signatureSvg);
  } catch (e) {
    if (e instanceof SvgError) throw new ApiError(400, "bad_signature");
    throw e;
  }

  return {
    plan,
    method,
    lang,
    email: mail,
    project: { name, type: project.type, description, domains: hosts },
    contract: { signer, signedAt, termsVersion: contract.termsVersion, signatureSvg },
  };
}
