// Лёгкий клиент Telegram Bot API без внешних зависимостей.
// Токен берётся из секрета Worker и никогда не попадает в логи или ответы.

export function createTelegramClient(token, fetchImpl = fetch) {
  async function call(method, body) {
    const res = await fetchImpl(`https://api.telegram.org/bot${token}/${method}`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body),
    });
    const data = await res.json();
    if (!data?.ok) {
      const err = new Error(data?.description || `Telegram API error on ${method}`);
      err.code = data?.error_code;
      err.method = method;
      throw err;
    }
    return data.result;
  }

  return {
    sendMessage: (chatId, text, extra = {}) =>
      call("sendMessage", { chat_id: chatId, text, parse_mode: "HTML", ...extra }),

    editMessageText: async (chatId, messageId, text, extra = {}) => {
      try {
        return await call("editMessageText", {
          chat_id: chatId,
          message_id: messageId,
          text,
          parse_mode: "HTML",
          ...extra,
        });
      } catch (err) {
        if (String(err.message).includes("message is not modified")) return null;
        throw err;
      }
    },

    answerCallbackQuery: async (callbackQueryId, extra = {}) => {
      try {
        return await call("answerCallbackQuery", { callback_query_id: callbackQueryId, ...extra });
      } catch {
        // Истёкший callback_query не должен ломать обработку
        return null;
      }
    },

    sendInvoice: (params) => call("sendInvoice", params),

    answerPreCheckoutQuery: (preCheckoutQueryId, ok, errorMessage) =>
      call("answerPreCheckoutQuery", {
        pre_checkout_query_id: preCheckoutQueryId,
        ok,
        ...(ok ? {} : { error_message: errorMessage }),
      }),

    refundStarPayment: (userId, telegramPaymentChargeId) =>
      call("refundStarPayment", {
        user_id: userId,
        telegram_payment_charge_id: telegramPaymentChargeId,
      }),

    setMyCommands: (commands, languageCode) =>
      call("setMyCommands", {
        commands,
        ...(languageCode ? { language_code: languageCode } : {}),
      }),

    setWebhook: (url, secretToken) =>
      call("setWebhook", {
        url,
        secret_token: secretToken,
        allowed_updates: ["message", "callback_query", "pre_checkout_query"],
      }),
  };
}
