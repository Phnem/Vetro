// Основной обработчик обновлений Telegram (команды, кнопки, анкета, оплата Stars, управление ключами).

import {
  claimPaidOrder,
  clearWizardSession,
  countActiveKeys,
  createApplication,
  createOrder,
  getApplication,
  getCustomerById,
  getCustomerByTelegramId,
  getCustomerKey,
  getOrderByChargeId,
  getOrderByIdOrCharge,
  getOrderByPayload,
  getSession,
  getSoldTiers,
  getTier,
  getUsageForKeys,
  insertApiKey,
  listCustomerKeys,
  markOrderFailed,
  markOrderRefunded,
  revokeApiKey,
  saveSession,
  setApplicationStatus,
  setCustomerLanguage,
  setOrderKeyId,
  updateApiKeyExpiry,
  upgradeApiKey,
  upsertCustomer,
} from "./db.js";
import {
  LANGUAGE_PROMPT,
  esc,
  fmtDateUtc,
  fmtNum,
  kindLabel,
  parseMenuAction,
  replyMenuKeyboard,
  texts,
  tierFeaturesText,
} from "./i18n.js";
import { newApiKey, newInvoicePayload } from "./keys.js";
import {
  STEPS,
  parseExpectedPerDay,
  parseOrigins,
  previousStep,
  renderStepPrompt,
  renderWizardTierInfo,
  validateAppName,
  validateDescription,
} from "./wizard.js";

function inferLang(session, customer) {
  if (session?.lang === "ru" || session?.lang === "en") return session.lang;
  if (customer?.language && String(customer.language).toLowerCase().startsWith("ru")) return "ru";
  return "en";
}

async function notifyOwner(tg, env, htmlText) {
  const ownerId = env.OWNER_TELEGRAM_ID;
  if (!ownerId) return;
  try {
    await tg.sendMessage(ownerId, htmlText);
  } catch {
    // Не прерываем сценарий покупателя, если владелец ещё не запускал бота
  }
}

async function sendTierInvoice(tg, db, env, { chatId, customer, applicationId, tier, kind, keyId = null, lang, appName, now }) {
  const t = texts(lang);
  const payload = newInvoicePayload();
  const order = await createOrder(
    db,
    {
      applicationId,
      customerId: customer.id,
      tier: tier.name,
      kind,
      stars: tier.price_stars,
      currency: "XTR",
      invoicePayload: payload,
      keyId,
    },
    now,
  );
  if (kind === "new") {
    await setApplicationStatus(db, applicationId, "invoiced", now);
  }

  const ru = lang === "ru";
  const kindText =
    kind === "renew"
      ? ru ? "Продление ключа" : "Key renewal"
      : kind === "upgrade"
        ? ru ? "Смена тарифа" : "Plan upgrade"
        : ru ? "Новый API-ключ" : "New API key";
  const title = `Vetro API — ${tier.title} (${tier.period_days}${ru ? " дн." : "d"})`;
  const description =
    `${kindText}${appName ? ` · ${appName}` : ""}. ` +
    (ru
      ? `Лимиты: ${tier.per_second}/с, ${tier.per_minute}/мин, ${fmtNum(tier.per_day, "ru")}/сут.`
      : `Limits: ${tier.per_second}/s, ${tier.per_minute}/min, ${fmtNum(tier.per_day, "en")}/day.`);

  try {
    await tg.sendInvoice({
      chat_id: chatId,
      title: title.slice(0, 32),
      description: description.slice(0, 255),
      payload,
      provider_token: "",
      currency: "XTR",
      prices: [{ label: `${tier.title} (${tier.period_days}d)`, amount: tier.price_stars }],
    });
    await tg.sendMessage(chatId, t.invoiceSent, { reply_markup: replyMenuKeyboard(lang) });
    return order;
  } catch (err) {
    await markOrderFailed(db, order.id);
    await tg.sendMessage(chatId, t.invoiceFailed, { reply_markup: replyMenuKeyboard(lang) });
    throw err;
  }
}

async function showPlans(tg, db, chatId, lang, editMessageId = null) {
  const t = texts(lang);
  const soldTiers = await getSoldTiers(db);
  if (!soldTiers.length) {
    await tg.sendMessage(chatId, t.noTiers, { reply_markup: replyMenuKeyboard(lang) });
    return;
  }
  const ru = lang === "ru";
  const summaryLines = soldTiers.map(
    (tier) =>
      `• <b>${esc(tier.title)}</b> — <b>${tier.price_stars} ⭐</b> / ${tier.period_days}${ru ? " дн." : "d"} ` +
      `(${fmtNum(tier.per_day, lang)} ${ru ? "запр./сут" : "req/day"})`,
  );
  const text = `${t.plansHeader}\n\n${summaryLines.join("\n")}`;
  const buttons = soldTiers.map((tier) => [
    { text: `ℹ️ ${tier.title} — ${tier.price_stars} ⭐`, callback_data: `info:tier:${tier.name}` },
  ]);
  buttons.push([{ text: ru ? "🔑 Оформить API-ключ" : "🔑 Get API Key", callback_data: "nav:buy" }]);
  const markup = { inline_keyboard: buttons };

  if (editMessageId) {
    await tg.editMessageText(chatId, editMessageId, text, { reply_markup: markup });
  } else {
    await tg.sendMessage(chatId, text, { reply_markup: markup });
  }
}

async function showKeys(tg, db, chatId, customerId, lang, now) {
  const t = texts(lang);
  const ru = lang === "ru";
  const keys = await listCustomerKeys(db, customerId);
  if (!keys.length) {
    await tg.sendMessage(chatId, t.noKeys, {
      reply_markup: {
        inline_keyboard: [[{ text: ru ? "🔑 Купить ключ" : "🔑 Buy Key", callback_data: "nav:buy" }]],
      },
    });
    return;
  }

  const lines = [t.keysHeader, ""];
  const buttons = [];
  for (const k of keys) {
    const expired = k.status === "active" && k.expires_at && k.expires_at <= now;
    const statusBadge =
      k.status === "revoked"
        ? ru ? "🚫 отозван" : "🚫 revoked"
        : expired
          ? ru ? "⌛ истёк" : "⌛ expired"
          : ru ? "✅ активен" : "✅ active";
    const originsNote = k.allowed_origins ? ` · origins: <code>${esc(k.allowed_origins)}</code>` : "";
    lines.push(
      `• <code>${esc(k.key_prefix)}</code> — <b>${esc(k.label || k.tier)}</b> (${esc(k.tier_title || k.tier)})\n` +
        `  ${statusBadge} · ${ru ? "до" : "until"} ${fmtDateUtc(k.expires_at, lang)}${originsNote}`,
    );
    if (k.status === "active") {
      buttons.push([
        { text: `🔄 ${k.key_prefix}`, callback_data: `key:rotate:${k.id}` },
        { text: ru ? "⏳ Продлить" : "⏳ Renew", callback_data: `key:renew:${k.id}` },
        { text: ru ? "⬆️ Тариф" : "⬆️ Upgrade", callback_data: `key:upgrade:${k.id}` },
        { text: "🚫", callback_data: `key:revoke:${k.id}` },
      ]);
    }
  }
  buttons.push([{ text: ru ? "➕ Купить новый ключ" : "➕ Buy New Key", callback_data: "nav:buy" }]);
  await tg.sendMessage(chatId, lines.join("\n"), { reply_markup: { inline_keyboard: buttons } });
}

async function showUsage(tg, db, chatId, customerId, lang, now) {
  const t = texts(lang);
  const ru = lang === "ru";
  const keys = await listCustomerKeys(db, customerId);
  if (!keys.length) {
    await tg.sendMessage(chatId, t.noKeys, { reply_markup: replyMenuKeyboard(lang) });
    return;
  }
  const todayUtc = new Date(now).toISOString().slice(0, 10);
  const monthStart = todayUtc.slice(0, 8) + "01";
  const rows = await getUsageForKeys(
    db,
    keys.map((k) => k.id),
    monthStart,
  );

  const byKey = new Map();
  for (const r of rows) {
    if (!byKey.has(r.key_id)) byKey.set(r.key_id, { today: {}, todayTotal: 0, monthTotal: 0 });
    const stat = byKey.get(r.key_id);
    if (r.route !== "_denied") {
      stat.monthTotal += r.requests;
      if (r.day === todayUtc) stat.todayTotal += r.requests;
    }
    if (r.day === todayUtc) {
      stat.today[r.route] = (stat.today[r.route] ?? 0) + r.requests;
    }
  }

  const lines = [t.usageHeader(todayUtc, monthStart), ""];
  for (const k of keys) {
    const stat = byKey.get(k.id);
    const limitLabel = k.tier_per_day ? fmtNum(k.tier_per_day, lang) : "∞";
    if (!stat) {
      lines.push(`• <code>${esc(k.key_prefix)}</code> (${esc(k.label || k.tier)}): ${t.noUsageYet}`);
      continue;
    }
    const routeParts = Object.entries(stat.today)
      .map(([route, n]) => `${esc(route)}: ${fmtNum(n, lang)}`)
      .join(", ");
    lines.push(
      `• <code>${esc(k.key_prefix)}</code> (<b>${esc(k.label || k.tier)}</b>)\n` +
        `  ${ru ? "Сегодня" : "Today"}: <b>${fmtNum(stat.todayTotal, lang)}</b> / ${limitLabel}` +
        (routeParts ? ` (${routeParts})` : "") +
        `\n  ${ru ? "За месяц" : "This month"}: <b>${fmtNum(stat.monthTotal, lang)}</b>`,
    );
  }
  await tg.sendMessage(chatId, lines.join("\n"), { reply_markup: replyMenuKeyboard(lang) });
}

async function startWizard(tg, db, chatId, telegramId, lang, preselectedTier = null, now = Date.now()) {
  const soldTiers = await getSoldTiers(db);
  if (!soldTiers.length) {
    await tg.sendMessage(chatId, texts(lang).noTiers, { reply_markup: replyMenuKeyboard(lang) });
    return;
  }
  const validPre = preselectedTier && soldTiers.some((x) => x.name === preselectedTier) ? preselectedTier : null;
  const draft = validPre ? { preselectedTier: validPre } : {};
  await saveSession(db, telegramId, { lang, step: STEPS.APP_NAME, draft }, now);
  const prompt = renderStepPrompt(STEPS.APP_NAME, lang, draft, soldTiers);
  await tg.sendMessage(chatId, prompt.text, { reply_markup: prompt.reply_markup });
}

async function handleWizardText(tg, db, chatId, telegramId, lang, session, text, now) {
  const t = texts(lang);
  const draft = { ...(session.draft ?? {}) };
  const soldTiers = await getSoldTiers(db);

  if (session.step === STEPS.APP_NAME) {
    const appName = validateAppName(text);
    if (!appName) {
      await tg.sendMessage(chatId, t.errAppName);
      return;
    }
    draft.app_name = appName;
    await saveSession(db, telegramId, { lang, step: STEPS.DESCRIPTION, draft }, now);
    const prompt = renderStepPrompt(STEPS.DESCRIPTION, lang, draft, soldTiers);
    await tg.sendMessage(chatId, prompt.text, { reply_markup: prompt.reply_markup });
    return;
  }

  if (session.step === STEPS.DESCRIPTION) {
    const description = validateDescription(text);
    if (!description) {
      await tg.sendMessage(chatId, t.errDescription);
      return;
    }
    draft.description = description;
    await saveSession(db, telegramId, { lang, step: STEPS.KIND, draft }, now);
    const prompt = renderStepPrompt(STEPS.KIND, lang, draft, soldTiers);
    await tg.sendMessage(chatId, prompt.text, { reply_markup: prompt.reply_markup });
    return;
  }

  if (session.step === STEPS.ORIGINS) {
    const origins = parseOrigins(text);
    if (!origins) {
      await tg.sendMessage(chatId, t.errOrigins);
      return;
    }
    draft.origins = origins;
    await saveSession(db, telegramId, { lang, step: STEPS.COMMERCIAL, draft }, now);
    const prompt = renderStepPrompt(STEPS.COMMERCIAL, lang, draft, soldTiers);
    await tg.sendMessage(chatId, prompt.text, { reply_markup: prompt.reply_markup });
    return;
  }

  if (session.step === STEPS.LOAD) {
    const n = parseExpectedPerDay(text);
    if (n === null) {
      await tg.sendMessage(chatId, t.errExpectedPerDay);
      return;
    }
    draft.expected_per_day = n;
    await saveSession(db, telegramId, { lang, step: STEPS.TIER, draft }, now);
    const prompt = renderStepPrompt(STEPS.TIER, lang, draft, soldTiers);
    await tg.sendMessage(chatId, prompt.text, { reply_markup: prompt.reply_markup });
  }
}

async function handlePreCheckout(tg, db, pcq, now) {
  const customer = await getCustomerByTelegramId(db, pcq.from.id);
  const session = await getSession(db, pcq.from.id);
  const lang = inferLang(session, customer);
  const t = texts(lang);

  if (!customer || customer.banned === 1) {
    await tg.answerPreCheckoutQuery(pcq.id, false, t.preCheckoutBanned);
    return;
  }

  const order = await getOrderByPayload(db, pcq.invoice_payload);
  if (
    !order ||
    order.status !== "pending" ||
    order.customer_id !== customer.id ||
    pcq.currency !== "XTR" ||
    pcq.total_amount !== order.stars
  ) {
    await tg.answerPreCheckoutQuery(pcq.id, false, t.preCheckoutBadOrder);
    return;
  }

  const tier = await getTier(db, order.tier);
  if (!tier || !tier.sold) {
    await tg.answerPreCheckoutQuery(pcq.id, false, t.preCheckoutBadOrder);
    return;
  }

  if (order.kind === "new") {
    const activeCount = await countActiveKeys(db, customer.id, now);
    if (activeCount >= tier.max_keys) {
      await tg.answerPreCheckoutQuery(pcq.id, false, t.preCheckoutLimit);
      return;
    }
  } else {
    const existing = await getCustomerKey(db, order.key_id, customer.id);
    if (!existing || existing.status !== "active") {
      await tg.answerPreCheckoutQuery(pcq.id, false, t.preCheckoutBadOrder);
      return;
    }
  }

  await tg.answerPreCheckoutQuery(pcq.id, true);
}

async function handleSuccessfulPayment(tg, db, env, message, customer, lang, now) {
  const t = texts(lang);
  const sp = message.successful_payment;
  const chargeId = sp.telegram_payment_charge_id;
  const payload = sp.invoice_payload;
  const chatId = message.chat.id;

  // Идемпотентность: повторная доставка того же платежа не должна выдавать второй ключ.
  const already = await getOrderByChargeId(db, chargeId);
  if (already) return;

  if (customer.banned === 1) {
    await tg.sendMessage(chatId, t.banned);
    return;
  }

  const order = await getOrderByPayload(db, payload);
  if (!order || order.customer_id !== customer.id) return;

  const claimed = await claimPaidOrder(db, payload, chargeId, now);
  if (!claimed) return;

  const tier = await getTier(db, order.tier);
  if (!tier) return;

  if (order.kind === "new") {
    const app = await getApplication(db, order.application_id);
    const key = await newApiKey(tier.name);
    const expiresAt = now + tier.period_days * 86_400_000;
    await insertApiKey(db, {
      id: key.id,
      hash: key.hash,
      prefix: key.prefix,
      tier: tier.name,
      label: app?.app_name ?? "API Key",
      owner: `telegram:${customer.telegram_id}`,
      customerId: customer.id,
      applicationId: order.application_id,
      allowedOrigins: app?.origins ?? null,
      createdAt: now,
      expiresAt,
    });
    await setOrderKeyId(db, order.id, key.id);
    await setApplicationStatus(db, order.application_id, "issued", now);

    await tg.sendMessage(chatId, t.keyWarning(key.plain, tier, app?.app_name ?? "", expiresAt), {
      reply_markup: replyMenuKeyboard(lang),
    });

    await notifyOwner(
      tg,
      env,
      `💰 <b>Оплата и выдача ключа</b>\n` +
        `Заказ #${order.id} · ${order.stars} ⭐ · тариф <b>${esc(tier.title)}</b>\n` +
        `Покупатель: ${customer.username ? "@" + esc(customer.username) : customer.telegram_id}\n` +
        `Проект: <b>${esc(app?.app_name ?? "-")}</b>\n` +
        `Ключ: <code>${esc(key.id)}</code> (<code>${esc(key.prefix)}</code>)`,
    );
    return;
  }

  const existing = await getCustomerKey(db, order.key_id, customer.id);
  if (!existing) return;
  const base = Math.max(now, existing.expires_at ?? now);
  const newExpiresAt = base + tier.period_days * 86_400_000;

  if (order.kind === "renew") {
    await updateApiKeyExpiry(db, existing.id, customer.id, newExpiresAt);
    if (order.application_id) {
      const app = await getApplication(db, order.application_id);
      if (app?.status === "invoiced") await setApplicationStatus(db, order.application_id, "issued", now);
    }
    await tg.sendMessage(chatId, t.renewedMsg(existing.key_prefix, tier, newExpiresAt), {
      reply_markup: replyMenuKeyboard(lang),
    });
    await notifyOwner(
      tg,
      env,
      `⏳ <b>Продление ключа</b>\nЗаказ #${order.id} · ${order.stars} ⭐ · ключ <code>${esc(existing.key_prefix)}</code> до ${fmtDateUtc(newExpiresAt, "ru")}`,
    );
    return;
  }

  if (order.kind === "upgrade") {
    await upgradeApiKey(db, existing.id, customer.id, tier.name, newExpiresAt);
    if (order.application_id) {
      const app = await getApplication(db, order.application_id);
      if (app?.status === "invoiced") await setApplicationStatus(db, order.application_id, "issued", now);
    }
    await tg.sendMessage(chatId, t.upgradedMsg(existing.key_prefix, tier, newExpiresAt), {
      reply_markup: replyMenuKeyboard(lang),
    });
    await notifyOwner(
      tg,
      env,
      `⬆️ <b>Смена тарифа</b>\nЗаказ #${order.id} · ${order.stars} ⭐ · ключ <code>${esc(existing.key_prefix)}</code> → <b>${esc(tier.title)}</b>`,
    );
  }
}

async function handleCallback(tg, db, env, cq, customer, session, lang, now) {
  const data = cq.data ?? "";
  const chatId = cq.message?.chat?.id ?? cq.from.id;
  const messageId = cq.message?.message_id;
  const t = texts(lang);
  const ru = lang === "ru";

  await tg.answerCallbackQuery(cq.id);

  // Выбор языка при /start или /lang
  if (data.startsWith("startlang:") || data.startsWith("lang:")) {
    const isStart = data.startsWith("startlang:");
    const newLang = data.endsWith(":ru") ? "ru" : "en";
    await setCustomerLanguage(db, cq.from.id, newLang);
    await saveSession(db, cq.from.id, { lang: newLang, step: null, draft: null }, now);
    await tg.sendMessage(chatId, texts(newLang).langSaved, { reply_markup: replyMenuKeyboard(newLang) });
    if (isStart) {
      await startWizard(tg, db, chatId, cq.from.id, newLang, null, now);
    }
    return;
  }

  if (data === "nav:plans") {
    await showPlans(tg, db, chatId, lang, messageId);
    return;
  }

  if (data === "nav:buy" || data.startsWith("nav:buy:")) {
    const preTier = data.startsWith("nav:buy:") ? data.slice("nav:buy:".length) : null;
    await startWizard(tg, db, chatId, cq.from.id, lang, preTier, now);
    return;
  }

  if (data.startsWith("info:tier:")) {
    const tierName = data.slice("info:tier:".length);
    const tier = await getTier(db, tierName);
    if (!tier || !tier.sold) return;
    await tg.editMessageText(chatId, messageId, tierFeaturesText(tier, lang), {
      reply_markup: {
        inline_keyboard: [
          [{ text: ru ? `💳 Оформить ${tier.title}` : `💳 Get ${tier.title}`, callback_data: `nav:buy:${tier.name}` }],
          [{ text: ru ? "⬅️ Все тарифы" : "⬅️ All Plans", callback_data: "nav:plans" }],
        ],
      },
    });
    return;
  }

  // Шаги мастера заявки
  if (data === "wiz:cancel") {
    await clearWizardSession(db, cq.from.id, lang, now);
    await tg.sendMessage(chatId, t.cancelled, { reply_markup: replyMenuKeyboard(lang) });
    return;
  }

  if (data === "wiz:back") {
    if (!session?.step) return;
    const prev = previousStep(session.step, session.draft);
    if (!prev) {
      await clearWizardSession(db, cq.from.id, lang, now);
      await tg.sendMessage(chatId, t.cancelled, { reply_markup: replyMenuKeyboard(lang) });
      return;
    }
    const soldTiers = await getSoldTiers(db);
    await saveSession(db, cq.from.id, { lang, step: prev, draft: session.draft }, now);
    const prompt = renderStepPrompt(prev, lang, session.draft, soldTiers);
    await tg.editMessageText(chatId, messageId, prompt.text, { reply_markup: prompt.reply_markup });
    return;
  }

  if (data === "wiz:tiers_info") {
    const soldTiers = await getSoldTiers(db);
    const info = renderWizardTierInfo(lang, soldTiers);
    await tg.editMessageText(chatId, messageId, info.text, { reply_markup: info.reply_markup });
    return;
  }

  if (data === "wiz:back_to_tiers") {
    const soldTiers = await getSoldTiers(db);
    const draft = session?.draft ?? {};
    await saveSession(db, cq.from.id, { lang, step: STEPS.TIER, draft }, now);
    const prompt = renderStepPrompt(STEPS.TIER, lang, draft, soldTiers);
    await tg.editMessageText(chatId, messageId, prompt.text, { reply_markup: prompt.reply_markup });
    return;
  }

  if (data.startsWith("wiz:kind:")) {
    const kind = data.slice("wiz:kind:".length);
    if (!["website", "mobile", "server", "other"].includes(kind)) return;
    const draft = { ...(session?.draft ?? {}), kind };
    if (kind !== "website") delete draft.origins;
    const nextStep = kind === "website" ? STEPS.ORIGINS : STEPS.COMMERCIAL;
    await saveSession(db, cq.from.id, { lang, step: nextStep, draft }, now);
    const prompt = renderStepPrompt(nextStep, lang, draft, await getSoldTiers(db));
    await tg.editMessageText(chatId, messageId, prompt.text, { reply_markup: prompt.reply_markup });
    return;
  }

  if (data.startsWith("wiz:comm:")) {
    const commercial = data.endsWith(":1") ? 1 : 0;
    const draft = { ...(session?.draft ?? {}), commercial };
    await saveSession(db, cq.from.id, { lang, step: STEPS.LOAD, draft }, now);
    const prompt = renderStepPrompt(STEPS.LOAD, lang, draft, await getSoldTiers(db));
    await tg.editMessageText(chatId, messageId, prompt.text, { reply_markup: prompt.reply_markup });
    return;
  }

  if (data.startsWith("wiz:load:")) {
    const val = data.slice("wiz:load:".length);
    const draft = { ...(session?.draft ?? {}) };
    draft.expected_per_day = val === "skip" ? null : Number(val);
    const soldTiers = await getSoldTiers(db);
    await saveSession(db, cq.from.id, { lang, step: STEPS.TIER, draft }, now);
    const prompt = renderStepPrompt(STEPS.TIER, lang, draft, soldTiers);
    await tg.editMessageText(chatId, messageId, prompt.text, { reply_markup: prompt.reply_markup });
    return;
  }

  if (data.startsWith("wiz:tier:")) {
    const tierName = data.slice("wiz:tier:".length);
    const soldTiers = await getSoldTiers(db);
    const chosen = soldTiers.find((x) => x.name === tierName);
    if (!chosen) return;

    const activeCount = await countActiveKeys(db, customer.id, now);
    if (activeCount >= chosen.max_keys) {
      await tg.sendMessage(chatId, t.maxKeysReached(chosen, activeCount), { reply_markup: replyMenuKeyboard(lang) });
      return;
    }

    const draft = { ...(session?.draft ?? {}), tier: chosen.name };
    await saveSession(db, cq.from.id, { lang, step: STEPS.TERMS, draft }, now);
    const prompt = renderStepPrompt(STEPS.TERMS, lang, draft, soldTiers);
    await tg.editMessageText(chatId, messageId, prompt.text, { reply_markup: prompt.reply_markup });
    return;
  }

  if (data === "wiz:accept") {
    const draft = session?.draft;
    if (!draft?.app_name || !draft?.description || !draft?.kind || !draft?.tier) {
      await startWizard(tg, db, chatId, cq.from.id, lang, null, now);
      return;
    }
    if (draft.kind === "website" && (!Array.isArray(draft.origins) || !draft.origins.length)) {
      await saveSession(db, cq.from.id, { lang, step: STEPS.ORIGINS, draft }, now);
      const prompt = renderStepPrompt(STEPS.ORIGINS, lang, draft, await getSoldTiers(db));
      await tg.sendMessage(chatId, prompt.text, { reply_markup: prompt.reply_markup });
      return;
    }

    const tier = await getTier(db, draft.tier);
    if (!tier || !tier.sold) {
      await tg.sendMessage(chatId, t.noTiers);
      return;
    }
    const activeCount = await countActiveKeys(db, customer.id, now);
    if (activeCount >= tier.max_keys) {
      await tg.sendMessage(chatId, t.maxKeysReached(tier, activeCount), { reply_markup: replyMenuKeyboard(lang) });
      return;
    }

    const originsJson = draft.kind === "website" ? JSON.stringify(draft.origins) : null;
    const app = await createApplication(
      db,
      {
        customerId: customer.id,
        appName: draft.app_name,
        description: draft.description,
        kind: draft.kind,
        origins: originsJson,
        commercial: draft.commercial ? 1 : 0,
        expectedPerDay: draft.expected_per_day ?? null,
        tier: tier.name,
        status: "new",
        termsAcceptedAt: now,
      },
      now,
    );
    await clearWizardSession(db, cq.from.id, lang, now);

    await notifyOwner(
      tg,
      env,
      `📝 <b>Новая заявка #${app.id}</b>\n` +
        `От: ${customer.username ? "@" + esc(customer.username) : customer.telegram_id} (${esc(customer.first_name || "")})\n` +
        `Проект: <b>${esc(app.app_name)}</b> (${esc(kindLabel(app.kind, "ru"))})\n` +
        `Описание: ${esc(app.description)}\n` +
        (app.origins ? `Домены: <code>${esc(app.origins)}</code>\n` : "") +
        `Коммерческий: ${app.commercial ? "да" : "нет"}` +
        (app.expected_per_day != null ? ` · ~${app.expected_per_day}/сут` : "") +
        `\nТариф: <b>${esc(tier.title)}</b> (${tier.price_stars} ⭐)`,
    );

    await sendTierInvoice(tg, db, env, {
      chatId,
      customer,
      applicationId: app.id,
      tier,
      kind: "new",
      lang,
      appName: app.app_name,
      now,
    });
    return;
  }

  // Действия с существующими ключами
  if (data.startsWith("key:rotate:")) {
    const keyId = data.slice("key:rotate:".length);
    const k = await getCustomerKey(db, keyId, customer.id);
    if (!k || k.status !== "active") {
      await tg.sendMessage(chatId, t.keyNotFound);
      return;
    }
    await tg.sendMessage(chatId, t.confirmRotate(k), {
      reply_markup: {
        inline_keyboard: [
          [
            { text: ru ? "✅ Да, перевыпустить" : "✅ Yes, Rotate", callback_data: `key:rotate_yes:${k.id}` },
            { text: ru ? "❌ Отмена" : "❌ Cancel", callback_data: "wiz:cancel" },
          ],
        ],
      },
    });
    return;
  }

  if (data.startsWith("key:rotate_yes:")) {
    const keyId = data.slice("key:rotate_yes:".length);
    const k = await getCustomerKey(db, keyId, customer.id);
    if (!k || k.status !== "active") {
      await tg.sendMessage(chatId, t.keyNotFound);
      return;
    }
    const revoked = await revokeApiKey(db, k.id, customer.id, now);
    if (!revoked) {
      await tg.sendMessage(chatId, t.keyNotFound);
      return;
    }
    const fresh = await newApiKey(k.tier);
    await insertApiKey(db, {
      id: fresh.id,
      hash: fresh.hash,
      prefix: fresh.prefix,
      tier: k.tier,
      label: k.label,
      owner: k.owner,
      customerId: customer.id,
      applicationId: k.application_id,
      allowedOrigins: k.allowed_origins,
      createdAt: now,
      expiresAt: k.expires_at,
    });
    await tg.sendMessage(chatId, t.rotatedKeyOnce(fresh.plain, k.key_prefix, k.expires_at), {
      reply_markup: replyMenuKeyboard(lang),
    });
    return;
  }

  if (data.startsWith("key:revoke:")) {
    const keyId = data.slice("key:revoke:".length);
    const k = await getCustomerKey(db, keyId, customer.id);
    if (!k || k.status !== "active") {
      await tg.sendMessage(chatId, t.keyNotFound);
      return;
    }
    await tg.sendMessage(chatId, t.confirmRevoke(k), {
      reply_markup: {
        inline_keyboard: [
          [
            { text: ru ? "🚫 Да, отозвать" : "🚫 Yes, Revoke", callback_data: `key:revoke_yes:${k.id}` },
            { text: ru ? "❌ Отмена" : "❌ Cancel", callback_data: "wiz:cancel" },
          ],
        ],
      },
    });
    return;
  }

  if (data.startsWith("key:revoke_yes:")) {
    const keyId = data.slice("key:revoke_yes:".length);
    const k = await getCustomerKey(db, keyId, customer.id);
    if (!k || k.status !== "active") {
      await tg.sendMessage(chatId, t.keyNotFound);
      return;
    }
    const revoked = await revokeApiKey(db, k.id, customer.id, now);
    if (!revoked) {
      await tg.sendMessage(chatId, t.keyNotFound);
      return;
    }
    await tg.sendMessage(chatId, t.revokedDone(k.key_prefix), { reply_markup: replyMenuKeyboard(lang) });
    return;
  }

  if (data.startsWith("key:renew:")) {
    const keyId = data.slice("key:renew:".length);
    const k = await getCustomerKey(db, keyId, customer.id);
    if (!k || k.status !== "active") {
      await tg.sendMessage(chatId, t.keyNotFound);
      return;
    }
    const tier = await getTier(db, k.tier);
    if (!tier || !tier.sold) {
      await tg.sendMessage(chatId, t.noTiers);
      return;
    }
    let appId = k.application_id;
    if (!appId) {
      const app = await createApplication(
        db,
        {
          customerId: customer.id,
          appName: k.label || "API Key",
          description: "Key renewal",
          kind: "other",
          origins: k.allowed_origins,
          commercial: 0,
          expectedPerDay: null,
          tier: tier.name,
          status: "invoiced",
          termsAcceptedAt: now,
        },
        now,
      );
      appId = app.id;
    }
    await sendTierInvoice(tg, db, env, {
      chatId,
      customer,
      applicationId: appId,
      tier,
      kind: "renew",
      keyId: k.id,
      lang,
      appName: k.label || k.key_prefix,
      now,
    });
    return;
  }

  if (data.startsWith("key:upgrade:")) {
    const keyId = data.slice("key:upgrade:".length);
    const k = await getCustomerKey(db, keyId, customer.id);
    if (!k || k.status !== "active") {
      await tg.sendMessage(chatId, t.keyNotFound);
      return;
    }
    const soldTiers = await getSoldTiers(db);
    const currentSort = k.tier_sort ?? 0;
    const higher = soldTiers.filter((x) => x.sort > currentSort && x.name !== k.tier);
    if (!higher.length) {
      await tg.sendMessage(chatId, t.alreadyTopTier, { reply_markup: replyMenuKeyboard(lang) });
      return;
    }
    await tg.sendMessage(chatId, t.upgradeChooseTier(k), {
      reply_markup: {
        inline_keyboard: [
          ...higher.map((tier) => [
            { text: `⬆️ ${tier.title} — ${tier.price_stars} ⭐`, callback_data: `key:upg_to:${k.id}:${tier.name}` },
          ]),
          [{ text: ru ? "❌ Отмена" : "❌ Cancel", callback_data: "wiz:cancel" }],
        ],
      },
    });
    return;
  }

  if (data.startsWith("key:upg_to:")) {
    const rest = data.slice("key:upg_to:".length);
    const sep = rest.indexOf(":");
    if (sep < 1) return;
    const keyId = rest.slice(0, sep);
    const tierName = rest.slice(sep + 1);
    const k = await getCustomerKey(db, keyId, customer.id);
    if (!k || k.status !== "active") {
      await tg.sendMessage(chatId, t.keyNotFound);
      return;
    }
    const tier = await getTier(db, tierName);
    if (!tier || !tier.sold) {
      await tg.sendMessage(chatId, t.noTiers);
      return;
    }
    let appId = k.application_id;
    if (!appId) {
      const app = await createApplication(
        db,
        {
          customerId: customer.id,
          appName: k.label || "API Key",
          description: `Key upgrade to ${tier.name}`,
          kind: "other",
          origins: k.allowed_origins,
          commercial: 0,
          expectedPerDay: null,
          tier: tier.name,
          status: "invoiced",
          termsAcceptedAt: now,
        },
        now,
      );
      appId = app.id;
    }
    await sendTierInvoice(tg, db, env, {
      chatId,
      customer,
      applicationId: appId,
      tier,
      kind: "upgrade",
      keyId: k.id,
      lang,
      appName: k.label || k.key_prefix,
      now,
    });
  }
}

export async function handleUpdate(update, env, tg, now = Date.now()) {
  const db = env.DB;

  if (update.pre_checkout_query) {
    await handlePreCheckout(tg, db, update.pre_checkout_query, now);
    return;
  }

  if (update.callback_query) {
    const cq = update.callback_query;
    const customer = await upsertCustomer(db, cq.from, now);
    const session = await getSession(db, cq.from.id);
    const lang = inferLang(session, customer);
    if (customer.banned === 1) {
      await tg.answerCallbackQuery(cq.id, { text: texts(lang).banned, show_alert: true });
      return;
    }
    await handleCallback(tg, db, env, cq, customer, session, lang, now);
    return;
  }

  const msg = update.message;
  if (!msg || !msg.from || msg.from.is_bot) return;
  if (msg.chat?.type && msg.chat.type !== "private") return;

  const customer = await upsertCustomer(db, msg.from, now);
  const session = await getSession(db, msg.from.id);
  const lang = inferLang(session, customer);
  const t = texts(lang);

  if (msg.refunded_payment) {
    const order = await getOrderByChargeId(db, msg.refunded_payment.telegram_payment_charge_id);
    if (order) {
      await markOrderRefunded(db, order.id, now);
      await tg.sendMessage(msg.chat.id, t.refundNotice(order.id, null), { reply_markup: replyMenuKeyboard(lang) });
    }
    return;
  }

  if (msg.successful_payment) {
    await handleSuccessfulPayment(tg, db, env, msg, customer, lang, now);
    return;
  }

  if (customer.banned === 1) {
    await tg.sendMessage(msg.chat.id, t.banned);
    return;
  }

  const text = msg.text;
  if (!text) return;

  const menu = parseMenuAction(text);
  if (menu) {
    const { action, arg } = menu;
    if (action === "start") {
      await clearWizardSession(db, msg.from.id, lang, now);
      await tg.sendMessage(msg.chat.id, LANGUAGE_PROMPT.text, {
        reply_markup: {
          inline_keyboard: [
            [
              { text: "🇬🇧 English", callback_data: "startlang:en" },
              { text: "🇷🇺 Русский", callback_data: "startlang:ru" },
            ],
          ],
        },
      });
      return;
    }

    if (action === "lang") {
      await tg.sendMessage(msg.chat.id, LANGUAGE_PROMPT.text, {
        reply_markup: LANGUAGE_PROMPT.reply_markup,
      });
      return;
    }

    if (action === "cancel") {
      await clearWizardSession(db, msg.from.id, lang, now);
      await tg.sendMessage(msg.chat.id, t.cancelled, { reply_markup: replyMenuKeyboard(lang) });
      return;
    }

    if (action === "help") {
      await tg.sendMessage(msg.chat.id, t.help, { reply_markup: replyMenuKeyboard(lang) });
      return;
    }

    if (action === "plans") {
      await showPlans(tg, db, msg.chat.id, lang);
      return;
    }

    if (action === "buy") {
      await startWizard(tg, db, msg.chat.id, msg.from.id, lang, arg?.toLowerCase() ?? null, now);
      return;
    }

    if (action === "key") {
      await clearWizardSession(db, msg.from.id, lang, now);
      await showKeys(tg, db, msg.chat.id, customer.id, lang, now);
      return;
    }

    if (action === "usage") {
      await clearWizardSession(db, msg.from.id, lang, now);
      await showUsage(tg, db, msg.chat.id, customer.id, lang, now);
      return;
    }

    if (action === "rotate" || action === "revoke") {
      await clearWizardSession(db, msg.from.id, lang, now);
      const keys = (await listCustomerKeys(db, customer.id)).filter((k) => k.status === "active");
      if (!keys.length) {
        await tg.sendMessage(msg.chat.id, t.noKeys, { reply_markup: replyMenuKeyboard(lang) });
        return;
      }
      const prefixCb = action === "rotate" ? "key:rotate:" : "key:revoke:";
      const promptText = action === "rotate" ? t.chooseKeyRotate : t.chooseKeyRevoke;
      await tg.sendMessage(msg.chat.id, promptText, {
        reply_markup: {
          inline_keyboard: keys.map((k) => [
            { text: `${k.key_prefix} (${k.label || k.tier})`, callback_data: `${prefixCb}${k.id}` },
          ]),
        },
      });
      return;
    }

    if (action === "refund") {
      if (!env.OWNER_TELEGRAM_ID || String(msg.from.id) !== String(env.OWNER_TELEGRAM_ID)) {
        await tg.sendMessage(msg.chat.id, t.help);
        return;
      }
      if (!arg) {
        await tg.sendMessage(msg.chat.id, "Usage: <code>/refund &lt;order_id|charge_id&gt;</code>");
        return;
      }
      const order = await getOrderByIdOrCharge(db, arg);
      if (!order || !order.charge_id || order.status !== "paid") {
        await tg.sendMessage(msg.chat.id, "Paid order with charge_id not found.");
        return;
      }
      const buyer = await getCustomerById(db, order.customer_id);
      if (!buyer) return;
      await tg.refundStarPayment(buyer.telegram_id, order.charge_id);
      await markOrderRefunded(db, order.id, now);
      await tg.sendMessage(msg.chat.id, `✅ Order #${order.id} refunded, key ${order.key_id ?? "-"} revoked.`);
      try {
        await tg.sendMessage(buyer.telegram_id, texts(inferLang(null, buyer)).refundNotice(order.id, null));
      } catch {
        // Игнорируем ошибку доставки покупателю
      }
      return;
    }
  }

  // Если пользователь сейчас в середине пошаговой анкеты — обрабатываем ввод
  if (session?.step) {
    await handleWizardText(tg, db, msg.chat.id, msg.from.id, lang, session, text, now);
    return;
  }

  // Любой другой текст вне анкеты — показать главное меню с кнопками
  await tg.sendMessage(msg.chat.id, t.mainMenu, { reply_markup: replyMenuKeyboard(lang) });
}
