package com.example.myapplication.network

import kotlinx.serialization.json.Json

/**
 * Общие настройки JSON. Раньше одна и та же конфигурация создавалась заново в 40 местах —
 * отдельный экземпляр со своим кэшем дескрипторов в каждом сторе и источнике.
 */

/** Чтение чужих ответов и своих файлов: незнакомые поля не ломают разбор. */
val AppJson: Json = Json { ignoreUnknownKeys = true }

/** Файловые сторы: как [AppJson], и поля со значением по умолчанию тоже пишутся в файл. */
val AppStoreJson: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
