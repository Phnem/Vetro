package com.example.myapplication.update

import com.example.myapplication.manga.translate.OfficialBuild

/**
 * Как приложение обновляется. Решает подпись APK, а не переключатель:
 *  - сборка, подписанная ключом автора (GitHub, Obtainium, Komi Store), обновляется сама: релизы
 *    проверяются, скачиваются и ставятся без участия пользователя;
 *  - всё остальное (F-Droid подписывает своим ключом, пересборки из исходников) не обновляется
 *    изнутри вовсе: там обновления приходят из магазина, и включить встроенные нельзя.
 */
enum class UpdateMode { Auto, Unavailable }

class UpdatePolicy(private val officialBuild: OfficialBuild) {

    val mode: UpdateMode by lazy {
        if (officialBuild.isOfficial) UpdateMode.Auto else UpdateMode.Unavailable
    }

    val isAuto: Boolean get() = mode == UpdateMode.Auto
}
