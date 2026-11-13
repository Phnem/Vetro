-- Журнал действий в панели (кто и что менял). Идемпотентно:
--   npx wrangler d1 execute vetro-books --remote --file schema/admin.sql
CREATE TABLE IF NOT EXISTS admin_log (
  id     INTEGER PRIMARY KEY AUTOINCREMENT,
  at     INTEGER NOT NULL,   -- мс
  actor  TEXT NOT NULL,      -- e-mail из токена Access
  action TEXT NOT NULL,      -- key.create | key.revoke | application.reject | ...
  target TEXT,               -- id ключа / заявки / установки
  detail TEXT                -- JSON с подробностями (без открытых ключей)
);
CREATE INDEX IF NOT EXISTS admin_log_at ON admin_log(at);
