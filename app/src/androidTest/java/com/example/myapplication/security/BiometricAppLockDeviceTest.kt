package com.example.myapplication.security

import android.content.Intent
import android.util.Log
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.myapplication.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Host harness supplies emulator fingerprint touches when these test-only markers appear. */
@RunWith(AndroidJUnit4::class)
class BiometricAppLockDeviceTest {
    @Test fun systemPromptConfirmsSettingsAndProtectsReturningToTheApp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageName.endsWith(".ab07smoke"))
        assertEquals(BiometricManager.BIOMETRIC_SUCCESS,
            BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK))
        val manager = AppLockManager.get(context)
        assertFalse(manager.enabled.value)
        val device = UiDevice.getInstance(instrumentation)
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        fun waitFor(description: String, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 20_000
            while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertTrue(description, condition())
        }
        fun prompt() { assertTrue(device.wait(Until.hasObject(By.text("Fingerprint or face")), 15_000)) }
        try {
            scenario.onActivity { AppLockActivity.changeSetting(it, true) }
            prompt()
            assertFalse(manager.enabled.value)
            device.findObject(By.text("Cancel")).click()
            waitFor("Cancel returns to the unprotected app") { !device.hasObject(By.text("Fingerprint or face")) }
            assertFalse(manager.enabled.value)

            scenario.onActivity { AppLockActivity.changeSetting(it, true) }
            prompt()
            val screenshot = File(context.getExternalFilesDir(null), "screens/biometric_system_prompt.png")
            screenshot.parentFile?.mkdirs()
            device.takeScreenshot(screenshot)
            Log.i("VetroBiometricTest", "AUTH_ENABLE")
            waitFor("Confirmed opt-in is persisted") { manager.enabled.value && !manager.locked }
            device.pressHome()
            waitFor("Leaving the app locks its session") { manager.locked }
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            prompt()
            assertTrue(manager.locked)
            device.findObject(By.text("Cancel")).click()
            waitFor("Cancel closes the system prompt") { !device.hasObject(By.text("Fingerprint or face")) }
            assertTrue("Cancel must not reveal the collection", manager.locked)
            assertTrue(device.hasObject(By.text("Unlock Vetro")))
            device.findObject(By.text("Authenticate")).click()
            prompt()
            Log.i("VetroBiometricTest", "AUTH_UNLOCK")
            waitFor("A successful fingerprint unlocks the app") { !manager.locked }
            scenario.onActivity {
                assertEquals(0, it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            }
            scenario.recreate()
            assertFalse("Rotation/recreation keeps the foreground session", manager.locked)

            scenario.onActivity { AppLockActivity.changeSetting(it, false) }
            prompt()
            assertTrue(manager.enabled.value)
            Log.i("VetroBiometricTest", "AUTH_DISABLE")
            waitFor("Confirmed opt-out is persisted") { !manager.enabled.value }
            device.pressHome()
            assertFalse(manager.locked)
        } finally {
            instrumentation.runOnMainSync { manager.authenticated(false) }
            scenario.close()
        }
    }
}
