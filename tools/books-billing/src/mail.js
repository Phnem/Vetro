// Письмо с ключом. Почтовый сервис не выбран (решает владелец), поэтому есть интерфейс транспорта и один переходник Resend,
// который включается только переменной MAIL_TRANSPORT=resend (+ секрет MAIL_API_KEY, переменная MAIL_FROM).
// Без транспорта: в тестовом режиме письмо пропускается, в боевом заказ получает email_failed = 1 (видно в панели).
// Ключ в очередь outbox и в логи не попадает: он существует только в памяти, в REVEAL (10 минут) и в этом письме.
//
// VETRO-TAIL[billing-mail]: ещё один транспорт = функция `async ({ to, subject, text }, env, fetchImpl)` (бросает при ошибке)
// и строка в TRANSPORTS. Кандидаты: Cloudflare Email Service, MailChannels.

const TRANSPORTS = {
  async resend({ to, subject, text }, env, fetchImpl) {
    if (!env.MAIL_API_KEY || !env.MAIL_FROM) throw new Error("mail_not_configured");
    const res = await fetchImpl("https://api.resend.com/emails", {
      method: "POST",
      headers: { authorization: `Bearer ${env.MAIL_API_KEY}`, "content-type": "application/json" },
      body: JSON.stringify({ from: env.MAIL_FROM, to: [to], subject, text }),
    });
    if (!res.ok) throw new Error("mail_http_" + res.status);
  },
};

/** Есть ли настроенный почтовый транспорт (для cron-напоминаний: без него письма не уходят, а отметка не ставится). */
export const hasMailTransport = (env) => Object.hasOwn(TRANSPORTS, env.MAIL_TRANSPORT ?? "");

const pad = (n) => String(n).padStart(2, "0");
export function fmtUtc(ms) {
  const d = new Date(ms);
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())} ${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`;
}

/** Лимиты тарифа одной строкой. Они посуточные (UTC) и от срока не зависят. 0 = без ограничения. */
export function fmtLimits(tier, lang) {
  const ru = lang === "ru";
  const f = (n, unit) => (Number(n) === 0 ? (ru ? "без ограничения" : "unlimited") : `${Number(n).toLocaleString("en-US")} ${unit}`);
  return ru
    ? `Лимиты: ${f(tier.per_second, "запр./с")}, ${f(tier.per_minute, "запр./мин")}, ${f(tier.per_day, "запр./сутки (UTC)")}.`
    : `Limits: ${f(tier.per_second, "req/s")}, ${f(tier.per_minute, "req/min")}, ${f(tier.per_day, "req/day (UTC)")}.`;
}

/** Тексты согласованы с docs брифа §7 (строка лимитов добавлена по правилам оплаты). `domains` — только для проекта-сайта. */
export function composeKeyEmail({ lang, signer, plan, expiresAt, key, domains, limits }) {
  const date = fmtUtc(expiresAt);
  if (lang === "ru") {
    const line = domains?.length ? `Ключ работает только с этих доменов: ${domains.join(", ")}.\n` : "";
    return {
      subject: "Ваш ключ Vetro API",
      text:
        `Здравствуйте, ${signer}!\n\n` +
        `Спасибо за заказ. Ваш ключ Vetro API (тариф ${plan}, действует до ${date} UTC):\n\n${key}\n\n` +
        `Это письмо — единственная копия: сам ключ мы не храним. Сохраните его в надёжном месте.\n\n` +
        (limits ? `${limits}\n\n` : "") +
        `Передавайте его в заголовке "Authorization: Bearer <ключ>" на https://api.vetrostudio.bid.\n${line}` +
        `Документация: https://vetrostudio.bid/api-docs.html\n\n` +
        `Потеряли ключ или есть вопрос? Напишите владельцу: https://t.me/Vetro_chat\n— Vetro Studio\n`,
    };
  }
  const line = domains?.length ? `The key works from these domains only: ${domains.join(", ")}.\n` : "";
  return {
    subject: "Your Vetro API key",
    text:
      `Hello, ${signer}!\n\n` +
      `Thank you for your order. Your Vetro API key (${plan}, valid until ${date} UTC):\n\n${key}\n\n` +
      `This email is the only copy: we do not store the key itself. Keep it somewhere safe.\n\n` +
      (limits ? `${limits}\n\n` : "") +
      `Use it in the header "Authorization: Bearer <key>" on https://api.vetrostudio.bid.\n${line}` +
      `Documentation: https://vetrostudio.bid/api-docs.html\n\n` +
      `Lost the key or have a question? Write to the owner: https://t.me/Vetro_chat\n— Vetro Studio\n`,
  };
}

/** ЧЕРНОВИК текста (владелец согласует с сайтом): напоминание за 3 дня до конца пакета. У подписки напоминаний нет: она продлевается сама. */
export function composeReminderEmail({ lang, signer, plan, expiresAt, prefix }) {
  const date = fmtUtc(expiresAt);
  const hello = (w) => `${w}${signer ? ", " + signer : ""}!`;
  if (lang === "ru") {
    return {
      subject: "Ключ Vetro API скоро закончится",
      text:
        `${hello("Здравствуйте")}\n\nКлюч ${prefix} (тариф ${plan}) действует до ${date} UTC. Пакет не продлевается сам.\n` +
        `Чтобы продлить, оформите тот же тариф для того же проекта на https://vetrostudio.bid/api.html: купленные дни прибавятся к текущему сроку, ключ останется прежним.\n\n` +
        `Вопросы: https://t.me/Vetro_chat\n— Vetro Studio\n`,
    };
  }
  return {
    subject: "Your Vetro API key expires soon",
    text:
      `${hello("Hello")}\n\nYour key ${prefix} (${plan}) is valid until ${date} UTC. This prepaid pack does not renew by itself.\n` +
      `To extend it, buy the same plan for the same project on https://vetrostudio.bid/api.html: the days you buy are added to the current term and the key stays the same.\n\n` +
      `Questions: https://t.me/Vetro_chat\n— Vetro Studio\n`,
  };
}

/** ЧЕРНОВИК текста (владелец согласует с сайтом): продление уже выданного ключа, сам ключ не повторяется. */
export function composeRenewalEmail({ lang, signer, plan, expiresAt, prefix }) {
  const date = fmtUtc(expiresAt);
  if (lang === "ru") {
    return {
      subject: "Ваш ключ Vetro API продлён",
      text:
        `Здравствуйте, ${signer}!\n\nСпасибо за заказ. Ключ ${prefix} (тариф ${plan}) продлён и действует до ${date} UTC.\n` +
        `Сам ключ не изменился: используйте тот же.\n\nЕсть вопрос? Напишите владельцу: https://t.me/Vetro_chat\n— Vetro Studio\n`,
    };
  }
  return {
    subject: "Your Vetro API key is extended",
    text:
      `Hello, ${signer}!\n\nThank you for your order. Your key ${prefix} (${plan}) is extended and valid until ${date} UTC.\n` +
      `The key itself is unchanged: keep using the same one.\n\nQuestions? Write to the owner: https://t.me/Vetro_chat\n— Vetro Studio\n`,
  };
}

/**
 * Отправка с тремя попытками. Возвращает "sent" | "skipped" (транспорта нет, тестовый режим) | "failed".
 * Ошибки транспорта не логируются: в них могут быть адрес получателя и тело письма с ключом.
 */
export async function sendKeyEmail(env, message, { fetchImpl = fetch, sleep = (ms) => new Promise((r) => setTimeout(r, ms)) } = {}) {
  const send = TRANSPORTS[env.MAIL_TRANSPORT];
  if (!send) return env.BILLING_MODE === "test" ? "skipped" : "failed";
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      await send(message, env, fetchImpl);
      return "sent";
    } catch {
      if (attempt < 2) await sleep(1000 * 3 ** attempt);
    }
  }
  return "failed";
}
