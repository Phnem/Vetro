# Local Folder (SAF) — AB-05 якорь

Проверено 2026-09-23 по [Android SAF](https://developer.android.com/training/data-storage/shared/documents-files), [ограничениям Android 11](https://developer.android.com/about/versions/11/privacy/storage), [DocumentsContract](https://developer.android.com/reference/android/provider/DocumentsContract.Document) и [DocumentFile](https://developer.android.com/reference/androidx/documentfile/provider/DocumentFile). Сетевых `robots.txt`, поиска и media-host нет; детали в `research/source-anchors.md`.

- `ACTION_OPEN_DOCUMENT_TREE` даёт выбранное пользователем дерево. Persistable grant сохраняется, но удаление/перемещение файла или отзыв доступа требуют явной обработки. На Android 11+ нельзя обещать выбор корня хранилища, `Download`, `Android/data`/`obb`.
- Внешний ключ `local:<tree-uri>:<opaque-document-id>`; путь и filename не являются стабильной глобальной identity. Разные DocumentsProvider могут отдавать неизвестные size/mtime и медленный обход.
- `InfrastructureGroup=local`, `SEARCH` по проиндексированным данным, `DETAILS`, `CHAPTERS`, `STREAM`, `OFFLINE`, `RIGHTS_VERIFIED` для своих файлов. Главы M4B/ID3 и seek проверять на Xiaomi в AB-09; нужны случаи grant/revoke, переименование и cloud-backed provider. Папка с нумерованными MP3 и обложкой — эталонный тест без внешнего сайта.

