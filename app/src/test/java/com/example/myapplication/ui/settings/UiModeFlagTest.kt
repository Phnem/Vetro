package com.example.myapplication.ui.settings

import com.example.myapplication.data.local.DevPreferencesKeys
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Один флаг интерфейса вместо трёх: новый интерфейс — по умолчанию, классический — по флагу. */
class UiModeFlagTest {

    @Test
    fun modern_ui_is_the_default() {
        assertTrue(SettingsUiState().modernUi)
    }

    @Test
    fun legacy_flag_switches_to_classic() {
        assertFalse(SettingsUiState(devLegacyUi = true).modernUi)
    }

    @Test
    fun retired_keys_never_collide_with_the_new_flag() {
        assertFalse(DevPreferencesKeys.LEGACY_UI in DevPreferencesKeys.RETIRED_UI_FLAGS)
        // Без повторов: каждый удалённый ключ в списке один раз.
        val names = DevPreferencesKeys.RETIRED_UI_FLAGS.map { it.name }
        assertTrue(names.toSet().size == names.size)
    }
}
