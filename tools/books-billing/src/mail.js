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

const pad = (n) => String(n).padStart(2, "0");
export function fmtUtc(ms) {
  const d = new Date(ms);
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())} ${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`;
}

/** Тексты согласованы с docs брифа §7. `domains` — только для проекта-сайта. */
export function composeKeyEmail({ lang, signer, plan, expiresAt, key, domains }) {
  const date = fmtUtc(expiresAt);
  if (lang === "ru") {
    const line = domains?.length ? `Ключ работает только с этих доменов: ${domains.join(", ")}.\n` : "";
    return {
      subject: "Ваш ключ Vetro API",
      text:
        `Здравствуйте, ${signer}!\n\n` +
        `Спасибо за заказ. Ваш ключ Vetro API (тариф ${plan}, действует до ${date} UTC):\n\n${key}\n\n` +
        `Это письмо — единственная копия: сам ключ мы не храним. Сохраните его в надёжном месте.\n\n` +
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
      `Use it in the header "Authorization: Bearer <key>" on https://api.vetrostudio.bid.\n${line}` +
      `Documentation: https://vetrostudio.bid/api-docs.html\n\n` +
      `Lost the key or have a question? Write to the owner: https://t.me/Vetro_chat\n— Vetro Studio\n`,
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
