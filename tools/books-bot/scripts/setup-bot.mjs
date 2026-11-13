// Одноразовая настройка Telegram-бота после деплоя Worker:
// регистрирует webhook (с X-Telegram-Bot-Api-Secret-Token) и меню команд для EN и RU.
//
// Запуск:
//   $env:TELEGRAM_BOT_TOKEN="..."
//   $env:TELEGRAM_WEBHOOK_SECRET="..."
//   $env:WEBHOOK_URL="https://vetro-books-bot.<subdomain>.workers.dev/webhook"
//   node scripts/setup-bot.mjs

import { createTelegramClient } from "../src/telegram.js";

const token = process.env.TELEGRAM_BOT_TOKEN;
const secret = process.env.TELEGRAM_WEBHOOK_SECRET;
const webhookUrl = process.env.WEBHOOK_URL;

if (!token) {
  console.error("Missing TELEGRAM_BOT_TOKEN environment variable.");
  process.exit(1);
}

const tg = createTelegramClient(token);

const commandsEn = [
  { command: "start", description: "Choose language & get started" },
  { command: "buy", description: "Apply for a new API key" },
  { command: "plans", description: "View plans, limits & prices" },
  { command: "key", description: "My API keys & actions" },
  { command: "usage", description: "View daily & monthly API usage" },
  { command: "rotate", description: "Rotate (reissue) an API key" },
  { command: "revoke", description: "Revoke an API key" },
  { command: "lang", description: "Switch language (EN / RU)" },
  { command: "cancel", description: "Cancel current step" },
];

const commandsRu = [
  { command: "start", description: "Выбрать язык и начать" },
  { command: "buy", description: "Оформить новый API-ключ" },
  { command: "plans", description: "Тарифы, лимиты и цены" },
  { command: "key", description: "Мои ключи и управление" },
  { command: "usage", description: "Расход API за день и месяц" },
  { command: "rotate", description: "Перевыпустить ключ" },
  { command: "revoke", description: "Отозвать ключ" },
  { command: "lang", description: "Сменить язык (EN / RU)" },
  { command: "cancel", description: "Отменить текущий шаг" },
];

await tg.setMyCommands(commandsEn);
await tg.setMyCommands(commandsEn, "en");
await tg.setMyCommands(commandsRu, "ru");
console.log("Commands registered (default, en, ru).");

if (webhookUrl) {
  if (!secret) {
    console.error("Missing TELEGRAM_WEBHOOK_SECRET environment variable for setWebhook.");
    process.exit(1);
  }
  await tg.setWebhook(webhookUrl, secret);
  console.log("Webhook configured:", webhookUrl);
} else {
  console.log("WEBHOOK_URL not set; skipped setWebhook.");
}
