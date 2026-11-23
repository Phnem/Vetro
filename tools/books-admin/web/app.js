(function () {
  "use strict";
  var root = document.getElementById("view");
  var tabs = ["Обзор", "Ключи", "Заявки", "Установки", "Тарифы", "Журнал"];
  var current = 0;

  // Все данные (названия проектов, описания, заметки) приходят от покупателей: в DOM они попадают только как текст.
  function h(tag, attrs) {
    var el = document.createElement(tag);
    for (var k in attrs || {}) {
      if (k === "class") el.className = attrs[k];
      else if (k.slice(0, 2) === "on") el.addEventListener(k.slice(2), attrs[k]);
      else if (attrs[k] === true) el.setAttribute(k, "");
      else if (attrs[k] !== false && attrs[k] != null) el.setAttribute(k, attrs[k]);
    }
    for (var i = 2; i < arguments.length; i++) append(el, arguments[i]);
    return el;
  }
  function append(el, c) {
    if (c == null || c === false) return;
    if (Array.isArray(c)) { c.forEach(function (x) { append(el, x); }); return; }
    el.appendChild(typeof c === "object" ? c : document.createTextNode(String(c)));
  }

  function api(path, method, body) {
    var opts = { method: method || "GET", headers: { "x-dash": "1" } };
    if (body !== undefined) { opts.headers["content-type"] = "application/json"; opts.body = JSON.stringify(body); }
    return fetch(path, opts).then(function (r) {
      return r.json().then(function (j) { if (!r.ok) throw new Error(j.error || r.status); return j; });
    });
  }

  function fail(e) { alert("Ошибка: " + e.message); }
  function when(ms) { return ms ? new Date(ms).toLocaleString("ru-RU") : "—"; }
  function num(n) { return Number(n || 0).toLocaleString("ru-RU"); }
  function usd(minor) { return "$" + (Number(minor || 0) / 100).toFixed(2); }
  function badge(s) { return h("span", { class: "badge b-" + s }, s); }
  function btn(label, fn, cls) { return h("button", { class: cls || "", onclick: fn }, label); }
  function td() { var c = h("td"); for (var i = 0; i < arguments.length; i++) append(c, arguments[i]); return c; }
  function tr(cells) { var r = h("tr"); cells.forEach(function (c) { r.appendChild(c); }); return r; }
  function table(head, rows) {
    return h("div", { class: "scroll" }, h("table", null,
      h("thead", null, h("tr", null, head.map(function (t) { return h("th", null, t); }))),
      h("tbody", null, rows)));
  }
  function stat(t, v) { return h("div", { class: "card" }, h("div", { class: "mute" }, t), h("div", { class: "big" }, v)); }
  function csv(s) { return s.split(",").map(function (x) { return x.trim(); }).filter(Boolean); }
  function act(path, body) { return api(path, "POST", body || {}).then(load).catch(fail); }

  // --- обзор ---
  function viewOverview() {
    return api("/api/overview").then(function (o) {
      var active = 0;
      o.keys.forEach(function (k) { if (k.status === "active") active += k.n; });
      var todayN = 0;
      o.usage.forEach(function (u) { if (u.day === o.today) todayN += u.n; });
      var cards = h("div", { class: "cards" },
        stat("Активных ключей", num(active)),
        stat("Запросов сегодня", num(todayN)),
        stat("Установок за 24 ч", num(o.installs_24h)),
        stat("Звёзд за 30 дней", num(o.paid_30d.stars) + " ⭐ (" + num(o.paid_30d.orders) + " заказов)"),
        stat("Доход сайта за 30 дней", usd(o.paid_web_30d.usd_minor) + " (" + num(o.paid_web_30d.orders) + " заказов)"),
        stat("Уведомлений в очереди", num(o.outbox_pending)));
      var days = {}, routes = {};
      o.usage.forEach(function (u) { (days[u.day] = days[u.day] || {})[u.route] = u.n; routes[u.route] = 1; });
      var rl = Object.keys(routes).sort();
      var dl = Object.keys(days).sort().reverse();
      var usage = table(["День"].concat(rl), dl.map(function (d) {
        return tr([td(d)].concat(rl.map(function (r) { return td(num(days[d][r])); })));
      }));
      var top = table(["Ключ", "Тариф", "Запросов"], o.top_keys_today.map(function (k) {
        return tr([td(k.label || k.key_id, " ", h("span", { class: "mute" }, k.key_id)), td(k.tier || "—"), td(num(k.n))]);
      }));
      var apps = o.applications.map(function (a) { return h("span", null, badge(a.status), " " + a.n + "  "); });
      return h("div", null, cards, h("h3", null, "Запросы за 14 дней"), usage, h("h3", null, "Самые активные ключи сегодня"), top,
        h("h3", null, "Заявки"), h("p", null, apps.length ? apps : "Пока нет"));
    });
  }

  // --- ключи ---
  function viewKeys() {
    return Promise.all([api("/api/keys"), api("/api/tiers")]).then(function (r) {
      var keys = r[0].keys, tiers = r[1].tiers;
      var out = h("div", { class: "box", hidden: true });
      var tier = h("select", null, tiers.map(function (t) { return h("option", { value: t.name }, t.name); }));
      var label = h("input", { placeholder: "Название (сайт, проект)" });
      var owner = h("input", { placeholder: "Владелец (site, partner:имя)" });
      var origins = h("input", { placeholder: "Домены через запятую (для сайтов)" });
      var days = h("input", { placeholder: "Дней (пусто = бессрочно)", size: "10" });
      var form = h("div", { class: "form" }, tier, label, owner, origins, days, btn("Выдать ключ", function () {
        api("/api/keys", "POST", { tier: tier.value, label: label.value, owner: owner.value || null, days: days.value || null, origins: csv(origins.value) }).then(function (k) {
          out.hidden = false; out.textContent = "";
          append(out, [h("div", null, "Ключ (показан один раз, сохраните): "), h("code", null, k.key), btn("Копировать", function () { navigator.clipboard.writeText(k.key); })]);
        }).catch(fail);
      }, "primary"));
      var rows = keys.map(function (k) {
        var who = k.username ? "@" + k.username : (k.owner || "—");
        return tr([
          td(k.label || "—", h("div", { class: "mute" }, k.id + " · " + k.key_prefix)),
          td(k.tier), td(badge(k.status)), td(who, k.app_name ? h("div", { class: "mute" }, k.app_name) : null),
          td(k.allowed_origins ? JSON.parse(k.allowed_origins).join(", ") : "любые"),
          td(k.expires_at ? when(k.expires_at) : "бессрочно"),
          td(btn("Расход", function () { usage(k.id); }),
            k.status === "active"
              ? btn("Отозвать", function () { if (confirm("Отозвать ключ " + k.id + "?")) act("/api/keys/" + k.id + "/revoke"); }, "danger")
              : btn("Вернуть", function () { act("/api/keys/" + k.id + "/restore"); }),
            btn("Изменить", function () { editKey(k); }))
        ]);
      });
      return h("div", null, h("h3", null, "Новый ключ"), form, out, h("h3", null, "Ключи"),
        table(["Ключ", "Тариф", "Статус", "Владелец", "Домены", "Действует до", ""], rows));
    });
  }
  function usage(id) {
    api("/api/keys/" + id + "/usage").then(function (r) {
      alert(r.usage.length ? r.usage.map(function (u) { return u.day + "  " + u.route + ": " + u.requests; }).join("\n") : "Запросов за 30 дней не было");
    }).catch(fail);
  }
  function editKey(k) {
    var t = prompt("Тариф (basic, pro, ultra, app)", k.tier); if (t == null) return;
    var l = prompt("Название", k.label || ""); if (l == null) return;
    var o = prompt("Домены через запятую (пусто = любые)", k.allowed_origins ? JSON.parse(k.allowed_origins).join(",") : ""); if (o == null) return;
    var d = prompt("Срок: дней от сегодня (пусто = не менять, 0 = бессрочно)", ""); if (d == null) return;
    var body = { tier: t, label: l, origins: csv(o) };
    if (d !== "") body.days = Number(d);
    api("/api/keys/" + k.id, "PATCH", body).then(load).catch(fail);
  }

  // --- заявки ---
  // Заказы с сайта (tools/books-billing). Подпись показывается только как <img src="data:...">: это не исполняемый SVG.
  function webOrdersBlock(orders) {
    var sig = h("div", { class: "box", hidden: true });
    var rows = orders.map(function (o) {
      return tr([
        td(h("b", null, o.project_name), h("div", { class: "mute" }, "#" + o.id + " · " + o.project_type + " · " + o.provider + "/" + o.method), h("div", { class: "mute" }, o.checkout_ref),
          o.origins ? h("div", { class: "mute" }, JSON.parse(o.origins).join(", ")) : null),
        td(o.project_description),
        td(o.email, h("div", { class: "mute" }, o.banned ? "забанен" : "")),
        td(o.tier, h("div", { class: "mute" }, usd(o.amount_minor) + " " + o.currency)),
        td(badge(o.status), h("div", { class: "mute" }, when(o.created_at)),
          o.note ? h("div", { class: "note" }, "Пометка: " + o.note) : null,
          o.email_failed ? h("div", { class: "note" }, "Письмо с ключом не ушло: отправьте ключ вручную") : null,
          o.key_id ? h("div", { class: "mute" }, o.key_id + " · " + o.key_prefix + " · " + o.key_status) : null),
        td(o.signer ? o.signer : "—", h("div", { class: "mute" }, o.accepted_at ? "условия приняты " + when(o.accepted_at) + " · версия " + o.terms_hash : "условия не приняты")),
        td(
          o.has_signature ? btn("Подпись", function () {
            api("/api/web-orders/" + o.id + "/signature").then(function (s) {
              sig.hidden = false; sig.textContent = "";
              append(sig, [h("div", { class: "mute" }, "Подпись: " + o.signer + " (заказ #" + o.id + ")"), h("img", { class: "sig", src: s.src, alt: "Подпись" })]);
            }).catch(fail);
          }) : null,
          btn(o.banned ? "Разбанить" : "Бан", function () { if (o.banned || confirm("Забанить и отозвать все ключи покупателя?")) act("/api/web-customers/" + o.customer_id + (o.banned ? "/unban" : "/ban")); }, o.banned ? "" : "danger"))
      ]);
    });
    return h("div", null, h("h3", null, "Заказы с сайта (" + orders.length + ")"),
      h("p", { class: "mute" }, "Возврат делается у платёжного провайдера; его событие отзывает ключ и ставит заказ в «refunded». Тестовые заказы (mock) помечены провайдером."),
      sig, table(["Проект", "Описание", "Покупатель", "Тариф", "Статус", "Условия", ""], rows));
  }

  function viewApplications() {
    return Promise.all([api("/api/applications"), api("/api/web-orders")]).then(function (res) {
      var r = res[0];
      var rows = r.applications.map(function (a) {
        return tr([
          td(h("b", null, a.app_name), h("div", { class: "mute" }, "#" + a.id + " · " + a.kind + (a.commercial ? " · коммерческое" : ""))),
          td(a.description, a.origins ? h("div", { class: "mute" }, JSON.parse(a.origins).join(", ")) : null, a.note ? h("div", { class: "note" }, "Заметка: " + a.note) : null),
          td(a.username ? "@" + a.username : (a.first_name || "—"), h("div", { class: "mute" }, "tg " + a.telegram_id + (a.banned ? " · забанен" : ""))),
          td(a.tier, h("div", { class: "mute" }, num(a.paid_stars) + " ⭐ · ключей " + a.active_keys)),
          td(badge(a.status), h("div", { class: "mute" }, when(a.created_at))),
          td(
            btn("Заметка", function () { var n = prompt("Заметка", a.note || ""); if (n != null) api("/api/applications/" + a.id, "PATCH", { note: n }).then(load).catch(fail); }),
            btn("Написать", function () { var t = prompt("Сообщение покупателю"); if (t) act("/api/message", { customer_id: a.customer_id, text: t }); }),
            a.status !== "rejected" ? btn("Отклонить", function () { var why = prompt("Причина (увидит покупатель)"); if (why) act("/api/applications/" + a.id + "/reject", { reason: why }); }, "danger") : null,
            btn(a.banned ? "Разбанить" : "Бан", function () { if (a.banned || confirm("Забанить и отозвать все ключи покупателя?")) act("/api/customers/" + a.customer_id + (a.banned ? "/unban" : "/ban")); }, a.banned ? "" : "danger"))
        ]);
      });
      return h("div", null, h("p", { class: "mute" }, "Возврат Stars делается в боте командой /refund; здесь заявка отклоняется, ключи отзываются, покупатель получает сообщение."),
        table(["Проект", "Описание", "Покупатель", "Тариф", "Статус", ""], rows), webOrdersBlock(res[1].orders));
    });
  }

  // --- установки приложения ---
  function viewInstalls() {
    return Promise.all([api("/api/installs"), api("/api/signatures")]).then(function (r) {
      var pkg = h("input", { placeholder: "com.phnem.vetro" });
      var sha = h("input", { placeholder: "SHA-256 подписи (64 hex)", size: "50" });
      var lab = h("input", { placeholder: "release | fdroid | debug-perf" });
      var form = h("div", { class: "form" }, pkg, sha, lab, btn("Разрешить подпись", function () {
        api("/api/signatures", "POST", { package: pkg.value, sha256: sha.value, label: lab.value || null }).then(load).catch(fail);
      }, "primary"));
      var sigs = table(["Пакет", "SHA-256", "Метка", "Статус", ""], r[1].signatures.map(function (s) {
        return tr([td(s.package), td(h("code", null, s.sha256.slice(0, 12) + "…" + s.sha256.slice(-6))), td(s.label || "—"), td(badge(s.enabled ? "active" : "revoked")),
          td(btn(s.enabled ? "Отключить" : "Включить", function () { act("/api/signatures/toggle", { package: s.package, sha256: s.sha256, enabled: !s.enabled }); }, s.enabled ? "danger" : ""))]);
      }));
      var ins = table(["Установка", "Пакет", "Защита", "Загрузчик", "Токенов", "Последний раз", "Статус", ""], r[0].installs.map(function (i) {
        return tr([td(h("code", null, i.id.slice(0, 8)), h("div", { class: "mute" }, "подпись " + i.signature.slice(0, 8))), td(i.package), td(i.security_level), td(i.boot_state || "—"),
          td(i.tokens_issued), td(when(i.last_seen)), td(badge(i.status)),
          td(btn(i.status === "active" ? "Отозвать" : "Вернуть", function () { act("/api/installs/" + i.id + (i.status === "active" ? "/revoke" : "/restore")); }, i.status === "active" ? "danger" : ""))]);
      }));
      return h("div", null, h("h3", null, "Разрешённые подписи приложения"), form, sigs, h("h3", null, "Установки (" + r[0].installs.length + ")"), ins);
    });
  }

  // --- тарифы ---
  var NUM = ["per_second", "per_minute", "per_day", "max_limit", "max_ids", "max_keys", "price_stars", "price_usd_minor", "period_days"];
  var NAMES = { per_second: "в секунду", per_minute: "в минуту", per_day: "в сутки", max_limit: "limit", max_ids: "ids", max_keys: "ключей", price_stars: "⭐", price_usd_minor: "цена, центы USD", period_days: "дней" };
  function viewTiers() {
    return api("/api/tiers").then(function (r) {
      var rows = r.tiers.map(function (t) {
        var inputs = {};
        NUM.forEach(function (f) { inputs[f] = h("input", { value: t[f], size: "7", inputmode: "numeric" }); });
        var covers = h("input", { type: "checkbox", checked: !!t.allow_covers });
        var index = h("input", { type: "checkbox", checked: !!t.allow_index });
        var sold = h("input", { type: "checkbox", checked: !!t.sold });
        var cells = [td(h("b", null, t.name)), td("обложки ", covers), td("индекс ", index), td("продаётся ", sold)];
        NUM.forEach(function (f) { cells.push(td(inputs[f])); });
        cells.push(td(btn("Сохранить", function () {
          var body = { allow_covers: covers.checked, allow_index: index.checked, sold: sold.checked };
          NUM.forEach(function (f) { body[f] = Number(inputs[f].value); });
          api("/api/tiers/" + t.name, "PATCH", body).then(function (x) { alert(x.note); load(); }).catch(fail);
        }, "primary")));
        return tr(cells);
      });
      return h("div", null, h("p", { class: "mute" }, "0 в лимитах = без ограничения."),
        table(["Тариф", "", "", ""].concat(NUM.map(function (f) { return NAMES[f]; }), [""]), rows));
    });
  }

  // --- журнал ---
  function viewLog() {
    return api("/api/log").then(function (r) {
      return table(["Когда", "Кто", "Действие", "Объект", "Подробности"], r.log.map(function (l) {
        return tr([td(when(l.at)), td(l.actor), td(l.action), td(l.target || "—"), td(h("code", null, l.detail || ""))]);
      }));
    });
  }

  var views = [viewOverview, viewKeys, viewApplications, viewInstalls, viewTiers, viewLog];
  function load() {
    root.textContent = "Загрузка…";
    views[current]().then(function (el) { root.textContent = ""; root.appendChild(el); }).catch(function (e) { root.textContent = "Ошибка: " + e.message; });
  }
  var nav = document.getElementById("tabs");
  tabs.forEach(function (name, i) {
    nav.appendChild(h("button", { class: "tab" + (i === 0 ? " on" : ""), onclick: function () {
      current = i;
      Array.prototype.forEach.call(nav.children, function (c, j) { c.className = "tab" + (j === i ? " on" : ""); });
      load();
    } }, name));
  });
  load();
})();
