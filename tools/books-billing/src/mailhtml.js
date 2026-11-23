// HTML-вёрстка писем платёжного Worker (ключ, продление, напоминание). Таблицы и inline-стили: так письмо одинаково открывается в Gmail,
// Apple Mail и Outlook. Тёмная тема через prefers-color-scheme (где почтовик её поддерживает), без картинок и внешних ресурсов.
// Всё, что приходит от покупателя (имя, домены), и ключ экранируются: в HTML попадает только текст.

export const SUPPORT_EMAIL = "support@vetrostudio.bid";
const SITE = "https://vetrostudio.bid";
const BRAND = "#E85002";

const esc = (s) => String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#39;");

/** Лимиты тарифа коротко: «5 req/s · 120 req/min · 20,000 req/day (UTC)». 0 = без ограничения. */
export function limitsShort(tier, lang) {
  const ru = lang === "ru";
  const f = (n, unit) => (Number(n) === 0 ? (ru ? "без ограничения" : "unlimited") : `${Number(n).toLocaleString("en-US")} ${unit}`);
  return ru
    ? `${f(tier.per_second, "запр./с")} · ${f(tier.per_minute, "запр./мин")} · ${f(tier.per_day, "запр./сутки (UTC)")}`
    : `${f(tier.per_second, "req/s")} · ${f(tier.per_minute, "req/min")} · ${f(tier.per_day, "req/day (UTC)")}`;
}

const STR = {
  en: {
    docs: "Open documentation",
    extend: "Extend the plan",
    foot: `Questions or lost key? Write to <a href="mailto:${SUPPORT_EMAIL}" style="color:${BRAND};text-decoration:none;">${SUPPORT_EMAIL}</a>`,
    why: "You are receiving this email because an order was placed with this address on vetrostudio.bid.",
  },
  ru: {
    docs: "Открыть документацию",
    extend: "Продлить тариф",
    foot: `Вопрос или потерян ключ? Напишите на <a href="mailto:${SUPPORT_EMAIL}" style="color:${BRAND};text-decoration:none;">${SUPPORT_EMAIL}</a>`,
    why: "Вы получили это письмо, потому что на сайте vetrostudio.bid оформлен заказ на этот адрес.",
  },
};

const FONT = "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";
const MONO = "ui-monospace,SFMono-Regular,Menlo,Consolas,'Liberation Mono',monospace";

function rowsHtml(rows) {
  return rows
    .filter(Boolean)
    .map(
      ([k, v]) =>
        `<tr><td class="row mu" style="padding:11px 0;border-top:1px solid #ececef;color:#6b6b73;font-size:14px;width:38%;vertical-align:top;">${esc(k)}</td>` +
        `<td class="row tx" style="padding:11px 0;border-top:1px solid #ececef;color:#18181b;font-size:14px;font-weight:600;vertical-align:top;">${esc(v)}</td></tr>`,
    )
    .join("");
}

/** Общая раскладка письма. Параметры текстовые; `key` и `usage` выводятся моноширинным блоком. */
function layout({ lang, preheader, title, greeting, lead, keyLabel, key, rows, usageLead, usage, button, button2Url }) {
  const t = STR[lang] ?? STR.en;
  const keyBlock = key
    ? `<p class="mu" style="margin:24px 0 8px;font-size:12px;letter-spacing:.06em;text-transform:uppercase;color:#6b6b73;">${esc(keyLabel)}</p>` +
      `<div class="code" style="background:#fff4ec;border:1px solid #ffd5bd;border-radius:10px;padding:16px 18px;font-family:${MONO};font-size:15px;line-height:1.5;color:#b13c00;word-break:break-all;">${esc(key)}</div>`
    : "";
  const usageBlock = usage
    ? `<p class="mu" style="margin:24px 0 8px;font-size:14px;line-height:1.5;color:#6b6b73;">${esc(usageLead)}</p>` +
      `<div class="code2" style="background:#f4f4f5;border-radius:10px;padding:14px 16px;font-family:${MONO};font-size:13px;line-height:1.5;color:#18181b;word-break:break-all;">${esc(usage)}</div>`
    : "";
  const btn = button
    ? `<table role="presentation" cellpadding="0" cellspacing="0" style="margin:28px 0 4px;"><tr><td style="background:${BRAND};border-radius:10px;">` +
      `<a href="${esc(button2Url)}" style="display:inline-block;padding:13px 24px;font-family:${FONT};font-size:15px;font-weight:600;color:#ffffff;text-decoration:none;">${esc(button)}</a></td></tr></table>`
    : "";
  return `<!doctype html>
<html lang="${lang}" xmlns="http://www.w3.org/1999/xhtml">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light dark">
<meta name="supported-color-schemes" content="light dark">
<title>${esc(title)}</title>
<style>
@media (prefers-color-scheme: dark) {
  .bg { background: #0e0e10 !important; }
  .card { background: #18181b !important; border-color: #2a2a2e !important; }
  .tx { color: #f2f2f4 !important; }
  .mu { color: #a1a1aa !important; }
  .row { border-color: #2a2a2e !important; }
  .code { background: #2a1a10 !important; border-color: #5c2c10 !important; color: #ffb27d !important; }
  .code2 { background: #0e0e10 !important; color: #e4e4e7 !important; }
}
@media (max-width: 620px) {
  .wrap { width: 100% !important; }
  .pad { padding: 26px 20px !important; }
  .code { font-size: 13px !important; }
}
</style>
</head>
<body class="bg" style="margin:0;padding:0;background:#f4f4f5;">
<div style="display:none;max-height:0;overflow:hidden;opacity:0;color:transparent;mso-hide:all;">${esc(preheader)}</div>
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" class="bg" style="background:#f4f4f5;">
<tr><td align="center" style="padding:32px 12px;">
<table role="presentation" width="560" cellpadding="0" cellspacing="0" class="wrap" style="width:560px;max-width:100%;">
<tr><td style="padding:0 4px 16px;font-family:${FONT};font-size:17px;font-weight:700;letter-spacing:-.01em;color:#18181b;"><span class="tx" style="color:#18181b;"><span style="color:${BRAND};">●</span>&nbsp; Vetro Studio</span></td></tr>
<tr><td class="card" style="background:#ffffff;border:1px solid #e4e4e7;border-radius:16px;overflow:hidden;">
<div style="height:4px;background:${BRAND};line-height:4px;font-size:0;">&nbsp;</div>
<table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr><td class="pad" style="padding:32px 32px 28px;font-family:${FONT};">
<h1 class="tx" style="margin:0 0 18px;font-size:24px;line-height:1.25;letter-spacing:-.02em;color:#18181b;">${esc(title)}</h1>
<p class="tx" style="margin:0 0 6px;font-size:15px;line-height:1.55;color:#18181b;">${esc(greeting)}</p>
<p class="mu" style="margin:0;font-size:15px;line-height:1.55;color:#52525b;">${esc(lead)}</p>
${keyBlock}
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="margin:24px 0 0;">${rowsHtml(rows)}<tr><td colspan="2" class="row" style="border-top:1px solid #ececef;font-size:0;line-height:0;">&nbsp;</td></tr></table>
${usageBlock}
${btn}
</td></tr></table>
</td></tr>
<tr><td style="padding:20px 8px 0;font-family:${FONT};font-size:13px;line-height:1.6;color:#71717a;text-align:center;" class="mu">
<p style="margin:0 0 6px;">${t.foot}</p>
<p style="margin:0;">${esc(t.why)}</p>
</td></tr>
</table>
</td></tr>
</table>
</body>
</html>`;
}

const DOCS = `${SITE}/api-docs.html`;
const BUY = `${SITE}/api.html`;

export function htmlKeyEmail({ lang, signer, plan, date, key, domains, limits }) {
  const ru = lang === "ru";
  const t = STR[lang] ?? STR.en;
  return layout({
    lang,
    preheader: ru ? "Ключ внутри. Он показывается один раз: сохраните это письмо." : "Your key is inside. It is shown once: keep this email.",
    title: ru ? "Ваш ключ Vetro API готов" : "Your Vetro API key is ready",
    greeting: ru ? `Здравствуйте, ${signer}!` : `Hello, ${signer}!`,
    lead: ru
      ? "Спасибо за заказ. Это письмо — единственная копия ключа: сам ключ мы не храним, только его хэш. Сохраните его в надёжном месте."
      : "Thank you for your order. This email is the only copy of your key: we do not store the key itself, only its hash. Keep it somewhere safe.",
    keyLabel: ru ? "Ваш ключ" : "Your API key",
    key,
    rows: [
      [ru ? "Тариф" : "Plan", plan],
      [ru ? "Действует до" : "Valid until", `${date} UTC`],
      limits ? [ru ? "Лимиты" : "Limits", limits] : null,
      domains?.length ? [ru ? "Домены" : "Domains", domains.join(", ")] : null,
    ],
    usageLead: ru ? "Передавайте ключ в заголовке на https://api.vetrostudio.bid:" : "Send it in this header on https://api.vetrostudio.bid:",
    usage: ru ? "Authorization: Bearer <ключ>" : "Authorization: Bearer <key>",
    button: t.docs,
    button2Url: DOCS,
  });
}

export function htmlRenewalEmail({ lang, signer, plan, date, prefix }) {
  const ru = lang === "ru";
  const t = STR[lang] ?? STR.en;
  return layout({
    lang,
    preheader: ru ? `Ключ продлён до ${date} UTC.` : `Your key is extended until ${date} UTC.`,
    title: ru ? "Ваш ключ Vetro API продлён" : "Your Vetro API key is extended",
    greeting: ru ? `Здравствуйте, ${signer}!` : `Hello, ${signer}!`,
    lead: ru ? "Спасибо за заказ. Сам ключ не изменился: используйте тот же." : "Thank you for your order. The key itself is unchanged: keep using the same one.",
    rows: [
      [ru ? "Ключ" : "Key", prefix],
      [ru ? "Тариф" : "Plan", plan],
      [ru ? "Действует до" : "Valid until", `${date} UTC`],
    ],
    button: t.docs,
    button2Url: DOCS,
  });
}

export function htmlReminderEmail({ lang, signer, plan, date, prefix }) {
  const ru = lang === "ru";
  const t = STR[lang] ?? STR.en;
  const name = signer ? (ru ? `Здравствуйте, ${signer}!` : `Hello, ${signer}!`) : ru ? "Здравствуйте!" : "Hello!";
  return layout({
    lang,
    preheader: ru ? `Ключ действует до ${date} UTC. Пакет не продлевается сам.` : `Your key is valid until ${date} UTC. This pack does not renew by itself.`,
    title: ru ? "Ключ Vetro API скоро закончится" : "Your Vetro API key expires soon",
    greeting: name,
    lead: ru
      ? "Пакет не продлевается сам. Чтобы продлить, оформите тот же тариф для того же проекта: купленные дни прибавятся к текущему сроку, ключ останется прежним."
      : "This prepaid pack does not renew by itself. To extend it, buy the same plan for the same project: the days you buy are added to the current term and the key stays the same.",
    rows: [
      [ru ? "Ключ" : "Key", prefix],
      [ru ? "Тариф" : "Plan", plan],
      [ru ? "Действует до" : "Valid until", `${date} UTC`],
    ],
    button: t.extend,
    button2Url: BUY,
  });
}
