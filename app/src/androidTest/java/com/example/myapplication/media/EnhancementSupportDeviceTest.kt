package com.example.myapplication.media

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.media.effect.enhancement.Enhancement
import com.google.android.gms.tasks.Tasks
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Эксперимент [11]: поддерживает ли устройство улучшение видео из Google Play services.
 * Только спрашивает; модуль не устанавливается и ничего не скачивается.
 */
@RunWith(AndroidJUnit4::class)
class EnhancementSupportDeviceTest {
    @Test fun report() {
        assumeTrue("The enhancement API needs Android 13", Build.VERSION.SDK_INT >= 33)
        val client = Enhancement.getClient(InstrumentationRegistry.getInstrumentation().targetContext)
        val supported = runCatching { Tasks.await(client.isDeviceSupported(), 60, TimeUnit.SECONDS) }
        val installed = runCatching { Tasks.await(client.isModuleInstalled(), 60, TimeUnit.SECONDS) }
        Log.i(TAG, "device=${Build.MANUFACTURER} ${Build.MODEL} api=${Build.VERSION.SDK_INT}")
        Log.i(TAG, "supported=${supported.getOrElse { it.cause ?: it }}")
        Log.i(TAG, "moduleInstalled=${installed.getOrElse { it.cause ?: it }}")
    }

    private companion object {
        const val TAG = "EnhancementSupport"
    }
}
