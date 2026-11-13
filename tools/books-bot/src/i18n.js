// Локализация (EN / RU), клавиатуры и форматирование сообщений.

export function esc(value) {
  return String(value ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

export function fmtNum(n, lang) {
  if (n === 0) return lang === "ru" ? "без ограничений" : "unlimited";
  return Number(n).toLocaleString(lang === "ru" ? "ru-RU" : "en-US");
}

export function fmtDateUtc(ms, lang) {
  if (!ms) return lang === "ru" ? "бессрочно" : "no expiry";
  return new Date(ms).toISOString().slice(0, 16).replace("T", " ") + " UTC";
}

export function replyMenuKeyboard(lang) {
  const b = lang === "ru"
    ? [
        [{ text: "🔑 Купить ключ" }, { text: "📋 Тарифы" }],
        [{ text: "🗝 Мои ключи" }, { text: "📊 Расход" }],
        [{ text: "🔄 Перевыпуск" }, { text: "🚫 Отозвать" }, { text: "🌐 Язык" }],
      ]
    : [
        [{ text: "🔑 Buy Key" }, { text: "📋 Plans" }],
        [{ text: "🗝 My Keys" }, { text: "📊 Usage" }],
        [{ text: "🔄 Rotate" }, { text: "🚫 Revoke" }, { text: "🌐 Language" }],
      ];
  return { keyboard: b, resize_keyboard: true, is_persistent: true };
}

const BUTTON_TO_ACTION = new Map([
  ["🔑 Buy Key", "buy"],
  ["🔑 Купить ключ", "buy"],
  ["📋 Plans", "plans"],
  ["📋 Тарифы", "plans"],
  ["🗝 My Keys", "key"],
  ["🗝 Мои ключи", "key"],
  ["📊 Usage", "usage"],
  ["📊 Расход", "usage"],
  ["🔄 Rotate", "rotate"],
  ["🔄 Перевыпуск", "rotate"],
  ["🚫 Revoke", "revoke"],
  ["🚫 Отозвать", "revoke"],
  ["🌐 Language", "lang"],
  ["🌐 Язык", "lang"],
]);

export function parseMenuAction(text) {
  if (!text) return null;
  const trimmed = text.trim();
  if (BUTTON_TO_ACTION.has(trimmed)) {
    return { action: BUTTON_TO_ACTION.get(trimmed), arg: null };
  }
  const m = trimmed.match(/^\/([a-zA-Z0-9_]+)(?:@\w+)?(?:\s+(.*))?$/);
  if (!m) return null;
  const cmd = m[1].toLowerCase();
  const arg = m[2]?.trim() || null;
  if (cmd === "keys") return { action: "key", arg };
  if (["start", "plans", "buy", "key", "rotate", "usage", "revoke", "lang", "cancel", "help", "refund"].includes(cmd)) {
    return { action: cmd, arg };
  }
  return null;
}

export const LANGUAGE_PROMPT = {
  text:
    "<b>Vetro Book Catalogue API</b> (<code>api.vetrostudio.bid</code>)\n\n" +
    "Welcome! Please choose your preferred language:\n" +
    "Добро пожаловать! Выберите язык интерфейса:",
  reply_markup: {
    inline_keyboard: [
      [
        { text: "🇬🇧 English", callback_data: "lang:en" },
        { text: "🇷🇺 Русский", callback_data: "lang:ru" },
      ],
    ],
  },
};

export function tierFeaturesText(tier, lang) {
  const yes = lang === "ru" ? "да" : "yes";
  const no = lang === "ru" ? "нет" : "no";
  if (lang === "ru") {
    return (
      `<b>${esc(tier.title)}</b> — <b>${tier.price_stars} ⭐</b> / ${tier.period_days} дн.\n` +
      `• В секунду: ${fmtNum(tier.per_second, lang)} | в минуту: ${fmtNum(tier.per_minute, lang)}\n` +
      `• В сутки (UTC): ${fmtNum(tier.per_day, lang)}\n` +
      `• Поиск / <code>?ids=</code>: до ${tier.max_limit} / до ${tier.max_ids}\n` +
      `• Обложки (<code>/covers/*</code>): ${tier.allow_covers ? yes : no}\n` +
      `• Файлы индекса (офлайн-каталог): ${tier.allow_index ? yes : no}\n` +
      `• Ключей на покупателя: до ${tier.max_keys}`
    );
  }
  return (
    `<b>${esc(tier.title)}</b> — <b>${tier.price_stars} ⭐</b> / ${tier.period_days}d\n` +
    `• Per second: ${fmtNum(tier.per_second, lang)} | per minute: ${fmtNum(tier.per_minute, lang)}\n` +
    `• Per day (UTC): ${fmtNum(tier.per_day, lang)}\n` +
    `• Search / <code>?ids=</code>: up to ${tier.max_limit} / up to ${tier.max_ids}\n` +
    `• Covers (<code>/covers/*</code>): ${tier.allow_covers ? yes : no}\n` +
    `• Index files (offline catalogue): ${tier.allow_index ? yes : no}\n` +
    `• Keys per customer: up to ${tier.max_keys}`
  );
}

export function kindLabel(kind, lang) {
  const map = {
    website: lang === "ru" ? "Сайт" : "Website",
    mobile: lang === "ru" ? "Мобильное приложение" : "Mobile app",
    server: lang === "ru" ? "Серверный скрипт / бот" : "Server script / bot",
    other: lang === "ru" ? "Другое" : "Other",
  };
  return map[kind] ?? kind;
}

export function termsText(lang) {
  if (lang === "ru") {
    return (
      "📜 <b>Условия использования API</b>\n\n" +
      "1. Ключ является личным: передавать, публиковать в открытом доступе или перепродавать его запрещено.\n" +
      "2. Запрещено копировать каталог целиком (исключение — тариф с доступом к файлам индекса, и только для личной офлайн-работы).\n" +
      "3. Обязательна ссылка на источники данных (в карточках книг указаны поле <code>cover.page</code> и источники описаний).\n" +
      "4. Часть обложек и описаний имеет ограничения по лицензии (в т.ч. FantLab, Википедия CC BY-SA).\n" +
      "5. Владелец API вправе отозвать ключ при нарушении условий или злоупотреблении."
    );
  }
  return (
    "📜 <b>API Terms of Use</b>\n\n" +
    "1. Your API key is personal: sharing, publishing, or reselling it is prohibited.\n" +
    "2. Scraping or copying the entire catalogue is prohibited (except on a plan with index file access, for personal offline use only).\n" +
    "3. Attribution to data sources is required (book records include <code>cover.page</code> and description source fields).\n" +
    "4. Some covers and descriptions are subject to third-party licenses (including FantLab and Wikipedia CC BY-SA).\n" +
    "5. The API owner reserves the right to revoke a key in case of abuse or violation of these terms."
  );
}

export function texts(lang) {
  const ru = lang === "ru";
  return {
    banned: ru
      ? "⛔ Доступ ограничен. Выдача ключей и оплата для этого аккаунта отключены."
      : "⛔ Access restricted. Key issuance and payments are disabled for this account.",
    langSaved: ru
      ? "✅ Язык установлен: <b>Русский</b>.\nИспользуйте кнопки меню ниже или команды."
      : "✅ Language set to <b>English</b>.\nUse the menu buttons below or commands.",
    mainMenu: ru
      ? "🏠 <b>Главное меню</b>\nAPI каталога книг Vetro (<code>api.vetrostudio.bid</code>)"
      : "🏠 <b>Main Menu</b>\nVetro Book Catalogue API (<code>api.vetrostudio.bid</code>)",
    help: ru
      ? "ℹ️ <b>Доступные команды</b>\n" +
        "/start — выбор языка и оформление ключа\n" +
        "/buy — оформить новый API-ключ\n" +
        "/plans — тарифы и лимиты\n" +
        "/key — мои ключи (префиксы, срок, действия)\n" +
        "/usage — расход запросов по дням\n" +
        "/rotate — перевыпустить ключ\n" +
        "/revoke — отозвать ключ\n" +
        "/lang — сменить язык\n" +
        "/cancel — отменить текущий шаг"
      : "ℹ️ <b>Available commands</b>\n" +
        "/start — choose language & apply for a key\n" +
        "/buy — apply for a new API key\n" +
        "/plans — view plans & limits\n" +
        "/key — my keys (prefixes, expiry, actions)\n" +
        "/usage — request usage stats\n" +
        "/rotate — rotate a key\n" +
        "/revoke — revoke a key\n" +
        "/lang — switch language\n" +
        "/cancel — cancel current step",
    cancelled: ru ? "✖️ Текущее действие отменено." : "✖️ Current action cancelled.",
    noTiers: ru ? "⚠️ Сейчас нет доступных тарифов для покупки." : "⚠️ No purchasable plans are currently available.",
    plansHeader: ru
      ? "📋 <b>Тарифы API Vetro</b>\nОплата в Telegram Stars (⭐). Нажмите на тариф, чтобы увидеть подробности или оформить ключ:"
      : "📋 <b>Vetro API Plans</b>\nPaid via Telegram Stars (⭐). Tap a plan to read full details or buy a key:",
    step1: ru
      ? "<b>Шаг 1/8 · Название приложения</b>\nВведите название вашего приложения, сайта или проекта (до 80 символов):"
      : "<b>Step 1/8 · Application Name</b>\nEnter the name of your app, website, or project (up to 80 characters):",
    errAppName: ru
      ? "⚠️ Название должно быть от 1 до 80 символов. Попробуйте ещё раз:"
      : "⚠️ Application name must be between 1 and 80 characters. Please try again:",
    step2: ru
      ? "<b>Шаг 2/8 · Краткое описание</b>\nОпишите вкратце приложение или сайт, где будет использоваться API (до 500 символов):"
      : "<b>Step 2/8 · Short Description</b>\nBriefly describe the app or website where this API will be used (up to 500 characters):",
    errDescription: ru
      ? "⚠️ Описание должно быть от 1 до 500 символов. Попробуйте ещё раз:"
      : "⚠️ Description must be between 1 and 500 characters. Please try again:",
    step3: ru
      ? "<b>Шаг 3/8 · Тип проекта</b>\nВыберите, где будет использоваться ключ:"
      : "<b>Step 3/8 · Project Type</b>\nChoose where the API key will be used:",
    step4: ru
      ? "<b>Шаг 4/8 · Домены сайта</b>\nУкажите домены через запятую, <b>без</b> <code>https://</code> и пути (например: <code>reader.example, *.reader.example</code>).\nКлюч будет работать только с этих доменов:"
      : "<b>Step 4/8 · Website Domains</b>\nEnter a comma-separated list of domains <b>without</b> <code>https://</code> or path (e.g. <code>reader.example, *.reader.example</code>).\nBrowser requests will only be accepted from these origins:",
    errOrigins: ru
      ? "⚠️ Некорректный список доменов. Укажите домены через запятую без схемы и пути, например: <code>reader.example, *.reader.example</code>"
      : "⚠️ Invalid domain list. Enter comma-separated domains without scheme or path, e.g. <code>reader.example, *.reader.example</code>",
    step5: ru
      ? "<b>Шаг 5/8 · Коммерческое использование</b>\nПроект является коммерческим?"
      : "<b>Step 5/8 · Commercial Use</b>\nIs this a commercial project?",
    step6: ru
      ? "<b>Шаг 6/8 · Ожидаемая нагрузка</b>\nСколько примерно запросов в сутки вы планируете? Отправьте число (или выберите/пропустите):"
      : "<b>Step 6/8 · Expected Daily Load</b>\nRoughly how many requests per day do you expect? Send a number (or pick/skip):",
    errExpectedPerDay: ru
      ? "⚠️ Введите целое неотрицательное число (например, <code>1500</code>) или нажмите «Пропустить»:"
      : "⚠️ Please enter a non-negative integer (e.g. <code>1500</code>) or tap Skip:",
    step7: (recTier, expected) => {
      if (ru) {
        const recLine = recTier
          ? expected != null
            ? `\n💡 По нагрузке (${fmtNum(expected, "ru")} запр./сут) рекомендуем: <b>${esc(recTier.title)}</b>`
            : ""
          : "";
        return `<b>Шаг 7/8 · Выбор тарифа</b>${recLine}\nВыберите тариф или нажмите «ℹ️ О тарифах» для сравнения:`;
      }
      const recLine = recTier
        ? expected != null
          ? `\n💡 Based on ${fmtNum(expected, "en")} req/day, we recommend: <b>${esc(recTier.title)}</b>`
          : ""
        : "";
      return `<b>Step 7/8 · Choose Plan</b>${recLine}\nSelect a plan or tap «ℹ️ Plan Info» to compare details:`;
    },
    maxKeysReached: (tier, activeCount) =>
      ru
        ? `⚠️ На тарифе <b>${esc(tier.title)}</b> максимум активных ключей на покупателя: <b>${tier.max_keys}</b> (у вас сейчас ${activeCount}).\nОтзовите старый ключ (/revoke), продлите существующий (/key) или выберите тариф выше.`
        : `⚠️ Plan <b>${esc(tier.title)}</b> allows up to <b>${tier.max_keys}</b> active key(s) per customer (you currently have ${activeCount}).\nRevoke an old key (/revoke), renew an existing one (/key), or choose a higher plan.`,
    tooManyOpenApps: (maxOpen) =>
      ru
        ? `⚠️ У вас уже открыто ${maxOpen} заявок в статусе ожидания/оплаты. Оплатите одну из них или дождитесь её завершения, прежде чем создавать новую.`
        : `⚠️ You already have ${maxOpen} open applications awaiting payment. Please complete or cancel an existing one before creating a new application.`,
    step8Header: ru ? "<b>Шаг 8/8 · Проверка заявки и условия</b>" : "<b>Step 8/8 · Review & Terms</b>",
    invoiceSent: ru
      ? "💳 Счёт в Telegram Stars отправлен выше. После оплаты ключ придёт автоматически."
      : "💳 Telegram Stars invoice sent above. Your key will be delivered automatically after payment.",
    invoiceFailed: ru
      ? "⚠️ Не удалось выставить счёт. Попробуйте снова через /buy."
      : "⚠️ Could not send the invoice. Please try again with /buy.",
    keyWarning: (keyPlain, tier, appName, expiresAt) =>
      ru
        ? `🎉 <b>Ключ выпущен!</b> (${esc(tier.title)} — ${esc(appName)})\n` +
          `Срок действия до: <b>${fmtDateUtc(expiresAt, "ru")}</b>\n\n` +
          `⚠️ <b>Сохраните ключ прямо сейчас — повторно показать его я не смогу!</b>\n\n` +
          `<pre><code>${esc(keyPlain)}</code></pre>\n\n` +
          `Передавайте в заголовке <code>Authorization: Bearer &lt;ключ&gt;</code> или <code>X-Api-Key</code>.`
        : `🎉 <b>API Key Issued!</b> (${esc(tier.title)} — ${esc(appName)})\n` +
          `Valid until: <b>${fmtDateUtc(expiresAt, "en")}</b>\n\n` +
          `⚠️ <b>Save this key now — I cannot show it again!</b>\n\n` +
          `<pre><code>${esc(keyPlain)}</code></pre>\n\n` +
          `Pass it via <code>Authorization: Bearer &lt;key&gt;</code> or <code>X-Api-Key</code>.`,
    renewedMsg: (prefix, tier, expiresAt) =>
      ru
        ? `✅ Ключ <code>${esc(prefix)}</code> продлён (${esc(tier.title)}).\nНовый срок: <b>${fmtDateUtc(expiresAt, "ru")}</b>.`
        : `✅ Key <code>${esc(prefix)}</code> renewed (${esc(tier.title)}).\nNew expiry: <b>${fmtDateUtc(expiresAt, "en")}</b>.`,
    upgradedMsg: (prefix, tier, expiresAt) =>
      ru
        ? `⬆️ Ключ <code>${esc(prefix)}</code> переведён на тариф <b>${esc(tier.title)}</b>.\nНовый срок: <b>${fmtDateUtc(expiresAt, "ru")}</b>.\nИзменения вступят в силу в течение минуты.`
        : `⬆️ Key <code>${esc(prefix)}</code> upgraded to <b>${esc(tier.title)}</b>.\nNew expiry: <b>${fmtDateUtc(expiresAt, "en")}</b>.\nNew limits take effect within 1 minute.`,
    noKeys: ru
      ? "🗝 У вас пока нет API-ключей. Нажмите «🔑 Купить ключ», чтобы оформить первый."
      : "🗝 You don't have any API keys yet. Tap «🔑 Buy Key» to get started.",
    keysHeader: ru ? "🗝 <b>Ваши API-ключи</b>" : "🗝 <b>Your API Keys</b>",
    chooseKeyRotate: ru
      ? "🔄 Выберите активный ключ для перевыпуска (старый ключ будет отозван):"
      : "🔄 Select an active key to rotate (the old key will be revoked):",
    confirmRotate: (k) =>
      ru
        ? `⚠️ Перевыпустить ключ <code>${esc(k.key_prefix)}</code> (${esc(k.label || k.tier)})?\nСтарый ключ перестанет работать в течение минуты.`
        : `⚠️ Rotate key <code>${esc(k.key_prefix)}</code> (${esc(k.label || k.tier)})?\nThe old key will stop working within 1 minute.`,
    rotatedKeyOnce: (keyPlain, prefixOld, expiresAt) =>
      ru
        ? `🔄 <b>Ключ перевыпущен!</b> Старый ключ <code>${esc(prefixOld)}</code> отозван.\n` +
          `Срок действия сохранён до: <b>${fmtDateUtc(expiresAt, "ru")}</b>\n\n` +
          `⚠️ <b>Сохраните новый ключ — повторно показать не смогу!</b>\n\n` +
          `<pre><code>${esc(keyPlain)}</code></pre>`
        : `🔄 <b>Key Rotated!</b> Old key <code>${esc(prefixOld)}</code> has been revoked.\n` +
          `Expiry preserved until: <b>${fmtDateUtc(expiresAt, "en")}</b>\n\n` +
          `⚠️ <b>Save the new key now — I cannot show it again!</b>\n\n` +
          `<pre><code>${esc(keyPlain)}</code></pre>`,
    chooseKeyRevoke: ru
      ? "🚫 Выберите активный ключ для отзыва:"
      : "🚫 Select an active key to revoke:",
    confirmRevoke: (k) =>
      ru
        ? `⚠️ Отозвать ключ <code>${esc(k.key_prefix)}</code> (${esc(k.label || k.tier)})?\nЭто действие необратимо.`
        : `⚠️ Revoke key <code>${esc(k.key_prefix)}</code> (${esc(k.label || k.tier)})?\nThis action cannot be undone.`,
    revokedDone: (prefix) =>
      ru
        ? `🚫 Ключ <code>${esc(prefix)}</code> отозван (перестанет приниматься в пределах минуты).`
        : `🚫 Key <code>${esc(prefix)}</code> revoked (takes effect within 1 minute).`,
    keyNotFound: ru ? "⚠️ Ключ не найден или уже отозван." : "⚠️ Key not found or already revoked.",
    usageHeader: (todayUtc, monthStart) =>
      ru
        ? `📊 <b>Расход API</b> (сегодня ${todayUtc} UTC и с ${monthStart})`
        : `📊 <b>API Usage</b> (today ${todayUtc} UTC & since ${monthStart})`,
    noUsageYet: ru ? "Пока запросов не зафиксировано." : "No requests recorded yet.",
    upgradeChooseTier: (k) =>
      ru
        ? `⬆️ Выберите новый тариф для ключа <code>${esc(k.key_prefix)}</code> (текущий: <b>${esc(k.tier_title || k.tier)}</b>):`
        : `⬆️ Choose a new plan for key <code>${esc(k.key_prefix)}</code> (current: <b>${esc(k.tier_title || k.tier)}</b>):`,
    alreadyTopTier: ru
      ? "👑 Этот ключ уже на максимальном тарифе. Вы можете продлить его через «⏳ Продлить»."
      : "👑 This key is already on the highest plan. You can extend it via «⏳ Renew».",
    preCheckoutBadOrder: ru ? "Заказ не найден или уже оплачен." : "Order not found or already processed.",
    preCheckoutBanned: ru ? "Покупка недоступна для этого аккаунта." : "Purchases are disabled for this account.",
    preCheckoutLimit: ru ? "Превышен лимит активных ключей на выбранном тарифе." : "Active key limit reached for this plan.",
    refundNotice: (orderId, keyPrefix) =>
      ru
        ? `↩️ Платёж по заказу #${orderId} возвращён.${keyPrefix ? ` Ключ <code>${esc(keyPrefix)}</code> отозван.` : ""}`
        : `↩️ Payment for order #${orderId} was refunded.${keyPrefix ? ` Key <code>${esc(keyPrefix)}</code> has been revoked.` : ""}`,
  };
}
