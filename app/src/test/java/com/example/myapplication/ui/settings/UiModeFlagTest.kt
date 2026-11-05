package com.example.myapplication.ui.settings

import com.example.myapplication.data.local.DevPreferencesKeys
import org.junit.Assert.assertTrue
import org.junit.Test

/** Новый интерфейс и анимация «назад» влиты в основу: флагов больше нет, старые ключи стираются. */
class UiModeFlagTest {

    @Test
    fun modern_ui_and_extera_back_are_always_on() {
        assertTrue(SettingsUiState().modernUi)
        assertTrue(SettingsUiState().devExteraBack)
    }

    @Test
    fun retired_keys_include_the_merged_flags_once() {
        val names = DevPreferencesKeys.RETIRED_UI_FLAGS.map { it.name }
        assertTrue("dev_legacy_ui" in names)
        assertTrue("dev_extera_back" in names)
        // Без повторов: каждый удалённый ключ в списке один раз.
        assertTrue(names.toSet().size == names.size)
    }
}
