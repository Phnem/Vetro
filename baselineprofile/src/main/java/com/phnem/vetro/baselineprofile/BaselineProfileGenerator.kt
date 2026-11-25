package com.phnem.vetro.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Генерирует baseline profile: `./gradlew :app:generateReleaseBaselineProfile` с подключённым
 * устройством/эмулятором API 33+. Результат ложится в
 * `app/src/release/generated/baselineProfiles/` и входит в релизную сборку (файлы в git).
 *
 * Добавляй `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`: иначе после прогона
 * тестируемое приложение удаляется вместе с данными (вход, коллекция), а профиль нужно снимать
 * на настоящей коллекции.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    /** Стартовый профиль — только путь до первого экрана: по нему R8 раскладывает первый DEX. */
    @Test
    fun startup() = rule.collect(
        packageName = targetPackage(),
        includeInStartupProfile = true,
    ) {
        coldStartToHome()
    }

    /** Остальные пути компилируются заранее, но в стартовый кластер не попадают. */
    @Test
    fun journeys() = rule.collect(
        packageName = targetPackage(),
        includeInStartupProfile = false,
    ) {
        coldStartToHome()
        swipeWorkspacePages()
        openFirstTitleAndBack()
        openBooksAndPlay()
        // Профиль сохраняется по broadcast, а HyperOS не доставляет его замороженному фоновому
        // приложению: путь обязан закончиться с приложением на переднем плане.
        startActivityAndWait()
    }
}
