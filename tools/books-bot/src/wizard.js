// Логика 8-шаговой анкеты (wizard) и валидации полей заявки.

import { esc, fmtNum, kindLabel, termsText, texts, tierFeaturesText } from "./i18n.js";

export const STEPS = {
  APP_NAME: "app_name",
  DESCRIPTION: "description",
  KIND: "kind",
  ORIGINS: "origins",
  COMMERCIAL: "commercial",
  LOAD: "expected_per_day",
  TIER: "tier",
  TERMS: "terms",
};

const DOMAIN_RE = /^(\*\.)?(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9-]{2,63}$/;

export function validateAppName(raw) {
  const s = String(raw ?? "").trim();
  if (s.length < 1 || s.length > 80) return null;
  return s;
}

export function validateDescription(raw) {
  const s = String(raw ?? "").trim();
  if (s.length < 1 || s.length > 500) return null;
  return s;
}

export function parseOrigins(raw) {
  const parts = String(raw ?? "")
    .split(",")
    .map((s) => s.trim().toLowerCase())
    .filter(Boolean);
  if (!parts.length || parts.length > 20) return null;
  const out = [];
  const seen = new Set();
  for (const p of parts) {
    if (p.length > 253 || p.includes("://") || p.includes("/") || p.includes("?") || p.includes("#") || p.includes(":") || /\s/.test(p)) {
      return null;
    }
    if (p !== "localhost" && !DOMAIN_RE.test(p)) return null;
    if (!seen.has(p)) {
      seen.add(p);
      out.push(p);
    }
  }
  if (JSON.stringify(out).length > 1000) return null;
  return out;
}

export function validateDraftForSubmit(draft) {
  if (!draft || typeof draft !== "object") return null;
  const app_name = validateAppName(draft.app_name);
  const description = validateDescription(draft.description);
  const kind = ["website", "mobile", "server", "other"].includes(draft.kind) ? draft.kind : null;
  if (!app_name || !description || !kind || !draft.tier) return null;

  let origins = null;
  if (kind === "website") {
    if (!Array.isArray(draft.origins)) return null;
    origins = parseOrigins(draft.origins.join(","));
    if (!origins) return null;
  }

  let expected_per_day = null;
  if (draft.expected_per_day != null) {
    expected_per_day = parseExpectedPerDay(String(draft.expected_per_day));
    if (expected_per_day === null) return null;
  }

  return {
    app_name,
    description,
    kind,
    origins,
    commercial: draft.commercial ? 1 : 0,
    expected_per_day,
    tier: String(draft.tier),
  };
}

export function parseExpectedPerDay(raw) {
  const compact = String(raw ?? "").replace(/[\s_]/g, "");
  if (!/^\d+$/.test(compact)) return null;
  const n = Number(compact);
  if (!Number.isSafeInteger(n) || n < 0 || n > 1_000_000_000) return null;
  return n;
}

export function recommendTier(soldTiers, expectedPerDay) {
  if (expectedPerDay == null || !soldTiers.length) return null;
  const sorted = [...soldTiers].sort((a, b) => a.sort - b.sort);
  return sorted.find((t) => t.per_day === 0 || t.per_day >= expectedPerDay) ?? sorted[sorted.length - 1];
}

export function previousStep(step, draft) {
  switch (step) {
    case STEPS.DESCRIPTION:
      return STEPS.APP_NAME;
    case STEPS.KIND:
      return STEPS.DESCRIPTION;
    case STEPS.ORIGINS:
      return STEPS.KIND;
    case STEPS.COMMERCIAL:
      return draft?.kind === "website" ? STEPS.ORIGINS : STEPS.KIND;
    case STEPS.LOAD:
      return STEPS.COMMERCIAL;
    case STEPS.TIER:
      return STEPS.LOAD;
    case STEPS.TERMS:
      return STEPS.TIER;
    default:
      return null;
  }
}

export function renderStepPrompt(step, lang, draft, soldTiers = []) {
  const t = texts(lang);
  const ru = lang === "ru";
  const cancelBtn = { text: ru ? "❌ Отмена" : "❌ Cancel", callback_data: "wiz:cancel" };
  const backBtn = { text: ru ? "⬅️ Назад" : "⬅️ Back", callback_data: "wiz:back" };

  switch (step) {
    case STEPS.APP_NAME:
      return {
        text: t.step1,
        reply_markup: { inline_keyboard: [[cancelBtn]] },
      };

    case STEPS.DESCRIPTION:
      return {
        text: t.step2,
        reply_markup: { inline_keyboard: [[backBtn, cancelBtn]] },
      };

    case STEPS.KIND:
      return {
        text: t.step3,
        reply_markup: {
          inline_keyboard: [
            [
              { text: ru ? "🌐 Сайт" : "🌐 Website", callback_data: "wiz:kind:website" },
              { text: ru ? "📱 Мобильное" : "📱 Mobile App", callback_data: "wiz:kind:mobile" },
            ],
            [
              { text: ru ? "🖥 Сервер / бот" : "🖥 Server / Bot", callback_data: "wiz:kind:server" },
              { text: ru ? "📦 Другое" : "📦 Other", callback_data: "wiz:kind:other" },
            ],
            [backBtn, cancelBtn],
          ],
        },
      };

    case STEPS.ORIGINS:
      return {
        text: t.step4,
        reply_markup: { inline_keyboard: [[backBtn, cancelBtn]] },
      };

    case STEPS.COMMERCIAL:
      return {
        text: t.step5,
        reply_markup: {
          inline_keyboard: [
            [
              { text: ru ? "✅ Да" : "✅ Yes", callback_data: "wiz:comm:1" },
              { text: ru ? "❌ Нет" : "❌ No", callback_data: "wiz:comm:0" },
            ],
            [backBtn, cancelBtn],
          ],
        },
      };

    case STEPS.LOAD:
      return {
        text: t.step6,
        reply_markup: {
          inline_keyboard: [
            [
              { text: "~1 000", callback_data: "wiz:load:1000" },
              { text: "~10 000", callback_data: "wiz:load:10000" },
              { text: "~100 000", callback_data: "wiz:load:100000" },
            ],
            [{ text: ru ? "⏭ Пропустить" : "⏭ Skip", callback_data: "wiz:load:skip" }],
            [backBtn, cancelBtn],
          ],
        },
      };

    case STEPS.TIER: {
      const rec = recommendTier(soldTiers, draft?.expected_per_day);
      const rows = soldTiers.map((tier) => {
        const isRec = rec && rec.name === tier.name;
        const starBadge = isRec ? (ru ? " (рекомендуем)" : " (recommended)") : "";
        return [
          {
            text: `${tier.title} — ${tier.price_stars} ⭐ / ${tier.period_days}${ru ? "д" : "d"}${starBadge}`,
            callback_data: `wiz:tier:${tier.name}`,
          },
        ];
      });
      rows.push([{ text: ru ? "ℹ️ Подробнее о тарифах" : "ℹ️ Plan Info", callback_data: "wiz:tiers_info" }]);
      rows.push([backBtn, cancelBtn]);
      return {
        text: t.step7(rec, draft?.expected_per_day),
        reply_markup: { inline_keyboard: rows },
      };
    }

    case STEPS.TERMS: {
      const chosenTier = soldTiers.find((x) => x.name === draft?.tier);
      const originsLine =
        draft?.kind === "website" && Array.isArray(draft?.origins)
          ? `\n• ${ru ? "Домены" : "Origins"}: <code>${esc(draft.origins.join(", "))}</code>`
          : "";
      const loadLine =
        draft?.expected_per_day != null
          ? `\n• ${ru ? "Нагрузка" : "Expected load"}: ${fmtNum(draft.expected_per_day, lang)} / ${ru ? "сут" : "day"}`
          : "";
      const tierLine = chosenTier
        ? `${esc(chosenTier.title)} (${chosenTier.price_stars} ⭐ / ${chosenTier.period_days}${ru ? " дн." : "d"})`
        : esc(draft?.tier);

      const summary =
        `${t.step8Header}\n\n` +
        `• ${ru ? "Проект" : "App"}: <b>${esc(draft?.app_name)}</b>\n` +
        `• ${ru ? "Описание" : "Description"}: ${esc(draft?.description)}\n` +
        `• ${ru ? "Тип" : "Type"}: ${esc(kindLabel(draft?.kind, lang))}` +
        originsLine +
        `\n• ${ru ? "Коммерческий" : "Commercial"}: ${draft?.commercial ? (ru ? "да" : "yes") : ru ? "нет" : "no"}` +
        loadLine +
        `\n• ${ru ? "Тариф" : "Plan"}: <b>${tierLine}</b>\n\n` +
        termsText(chosenTier, lang);

      return {
        text: summary,
        reply_markup: {
          inline_keyboard: [
            [{ text: ru ? "✅ Принимаю условия и оплатить" : "✅ I Agree & Pay", callback_data: "wiz:accept" }],
            [
              { text: ru ? "⬅️ Изменить тариф" : "⬅️ Change Plan", callback_data: "wiz:back" },
              cancelBtn,
            ],
          ],
        },
      };
    }

    default:
      return {
        text: t.step1,
        reply_markup: { inline_keyboard: [[cancelBtn]] },
      };
  }
}

export function renderWizardTierInfo(lang, soldTiers) {
  const ru = lang === "ru";
  const body = soldTiers.map((tier) => tierFeaturesText(tier, lang)).join("\n\n");
  const header = ru ? "ℹ️ <b>Сравнение тарифов</b>\n\n" : "ℹ️ <b>Plan Details</b>\n\n";
  return {
    text: header + body,
    reply_markup: {
      inline_keyboard: [
        [{ text: ru ? "⬅️ Вернуться к выбору тарифа" : "⬅️ Back to plan selection", callback_data: "wiz:back_to_tiers" }],
      ],
    },
  };
}
