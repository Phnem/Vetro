package com.phnem.vetro.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Генерирует baseline profile: `./gradlew :app:generateReleaseBaselineProfile` с подключённым
 * устройством/эмулятором API 33+. Результат ложится в
 * `app/src/release/generated/baselineProfiles/` и входит в релизную сборку.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = targetPackage(),
        // Старт — отдельный профиль: его классы грузятся раньше остальных.
        includeInStartupProfile = true,
    ) {
        coldStartToHome()
        swipeWorkspacePages()
        openFirstTitleAndBack()
    }
}
