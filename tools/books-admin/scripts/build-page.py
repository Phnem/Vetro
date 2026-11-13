"""Собрать src/page.js из web/app.js, web/app.css, web/index.html. Запуск из tools/books-admin: python scripts/build-page.py"""
import json
out = "// Сгенерировано из web/* (python scripts/build-page.py): страница панели вшита в воркер строками.\n"
for name, f in [("APP_JS", "app.js"), ("APP_CSS", "app.css"), ("INDEX_HTML", "index.html")]:
    out += "export const " + name + " = " + json.dumps(open("web/" + f, encoding="utf-8").read(), ensure_ascii=False) + ";\n"
open("src/page.js", "w", encoding="utf-8", newline="\n").write(out)
