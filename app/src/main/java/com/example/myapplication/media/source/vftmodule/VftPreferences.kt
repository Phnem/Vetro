package com.example.myapplication.media.source.vftmodule

import android.content.Context
import android.content.SharedPreferences

/**
 * Настройки модуля VFTmodule (торрент-поиск и стриминг).
 */
class VftPreferences(
    private val prefs: SharedPreferences? = null,
) {
    constructor(context: Context) : this(getPrefsSafe(context))

    private val memory = mutableMapOf<String, Any>()

    var isTorrServerEnabled: Boolean
        get() = prefs?.getBoolean(KEY_TORRSERVER_ENABLED, false) ?: (memory[KEY_TORRSERVER_ENABLED] as? Boolean ?: false)
        set(value) {
            prefs?.edit()?.putBoolean(KEY_TORRSERVER_ENABLED, value)?.apply()
            memory[KEY_TORRSERVER_ENABLED] = value
        }

    var torrServerUrl: String
        get() = prefs?.getString(KEY_TORRSERVER_URL, DEFAULT_TORRSERVER_URL)
            ?: (memory[KEY_TORRSERVER_URL] as? String ?: DEFAULT_TORRSERVER_URL)
        set(value) {
            val v = value.trim().trimEnd('/')
            prefs?.edit()?.putString(KEY_TORRSERVER_URL, v)?.apply()
            memory[KEY_TORRSERVER_URL] = v
        }

    var isJackettEnabled: Boolean
        get() = prefs?.getBoolean(KEY_JACKETT_ENABLED, false) ?: (memory[KEY_JACKETT_ENABLED] as? Boolean ?: false)
        set(value) {
            prefs?.edit()?.putBoolean(KEY_JACKETT_ENABLED, value)?.apply()
            memory[KEY_JACKETT_ENABLED] = value
        }

    var jackettUrl: String
        get() = prefs?.getString(KEY_JACKETT_URL, DEFAULT_JACKETT_URL)
            ?: (memory[KEY_JACKETT_URL] as? String ?: DEFAULT_JACKETT_URL)
        set(value) {
            val v = value.trim().trimEnd('/')
            prefs?.edit()?.putString(KEY_JACKETT_URL, v)?.apply()
            memory[KEY_JACKETT_URL] = v
        }

    var jackettApiKey: String
        get() = prefs?.getString(KEY_JACKETT_API_KEY, "") ?: (memory[KEY_JACKETT_API_KEY] as? String ?: "")
        set(value) {
            val v = value.trim()
            prefs?.edit()?.putString(KEY_JACKETT_API_KEY, v)?.apply()
            memory[KEY_JACKETT_API_KEY] = v
        }

    var isRutorEnabled: Boolean
        get() = prefs?.getBoolean(KEY_RUTOR_ENABLED, true) ?: (memory[KEY_RUTOR_ENABLED] as? Boolean ?: true)
        set(value) {
            prefs?.edit()?.putBoolean(KEY_RUTOR_ENABLED, value)?.apply()
            memory[KEY_RUTOR_ENABLED] = value
        }

    var isRutrackerEnabled: Boolean
        get() = prefs?.getBoolean(KEY_RUTRACKER_ENABLED, true) ?: (memory[KEY_RUTRACKER_ENABLED] as? Boolean ?: true)
        set(value) {
            prefs?.edit()?.putBoolean(KEY_RUTRACKER_ENABLED, value)?.apply()
            memory[KEY_RUTRACKER_ENABLED] = value
        }

    var isNyaaEnabled: Boolean
        get() = prefs?.getBoolean(KEY_NYAA_ENABLED, true) ?: (memory[KEY_NYAA_ENABLED] as? Boolean ?: true)
        set(value) {
            prefs?.edit()?.putBoolean(KEY_NYAA_ENABLED, value)?.apply()
            memory[KEY_NYAA_ENABLED] = value
        }

    var isPirateBayEnabled: Boolean
        get() = prefs?.getBoolean(KEY_TPB_ENABLED, true) ?: (memory[KEY_TPB_ENABLED] as? Boolean ?: true)
        set(value) {
            prefs?.edit()?.putBoolean(KEY_TPB_ENABLED, value)?.apply()
            memory[KEY_TPB_ENABLED] = value
        }

    var preferOriginalAudio: Boolean
        get() = prefs?.getBoolean(KEY_PREFER_ORIGINAL_AUDIO, false) ?: (memory[KEY_PREFER_ORIGINAL_AUDIO] as? Boolean ?: false)
        set(value) {
            prefs?.edit()?.putBoolean(KEY_PREFER_ORIGINAL_AUDIO, value)?.apply()
            memory[KEY_PREFER_ORIGINAL_AUDIO] = value
        }

    var useEmbeddedEngine: Boolean
        get() = prefs?.getBoolean(KEY_USE_EMBEDDED_ENGINE, true) ?: (memory[KEY_USE_EMBEDDED_ENGINE] as? Boolean ?: true)
        set(value) {
            prefs?.edit()?.putBoolean(KEY_USE_EMBEDDED_ENGINE, value)?.apply()
            memory[KEY_USE_EMBEDDED_ENGINE] = value
        }

    var isModuleEnabled: Boolean
        get() = prefs?.getBoolean(KEY_MODULE_ENABLED, true) ?: (memory[KEY_MODULE_ENABLED] as? Boolean ?: true)
        set(value) {
            prefs?.edit()?.putBoolean(KEY_MODULE_ENABLED, value)?.apply()
            memory[KEY_MODULE_ENABLED] = value
        }

    companion object {
        private const val PREFS_NAME = "vft_module_prefs"
        private const val KEY_MODULE_ENABLED = "module_enabled"
        private const val KEY_TORRSERVER_ENABLED = "torrserver_enabled"
        private const val KEY_TORRSERVER_URL = "torrserver_url"
        private const val KEY_JACKETT_ENABLED = "jackett_enabled"
        private const val KEY_JACKETT_URL = "jackett_url"
        private const val KEY_JACKETT_API_KEY = "jackett_api_key"
        private const val KEY_RUTOR_ENABLED = "rutor_enabled"
        private const val KEY_RUTRACKER_ENABLED = "rutracker_enabled"
        private const val KEY_NYAA_ENABLED = "nyaa_enabled"
        private const val KEY_TPB_ENABLED = "tpb_enabled"
        private const val KEY_PREFER_ORIGINAL_AUDIO = "prefer_original_audio"
        private const val KEY_USE_EMBEDDED_ENGINE = "use_embedded_engine"

        const val DEFAULT_TORRSERVER_URL = "http://127.0.0.1:8090"
        const val DEFAULT_JACKETT_URL = "http://127.0.0.1:9117"

        private fun getPrefsSafe(context: Context): SharedPreferences? = try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (e: Throwable) {
            null
        }
    }
}
