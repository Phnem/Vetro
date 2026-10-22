package com.example.myapplication.security

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.AtomicFile
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/** Covers every activity before its first frame, including direct reader/player entry points. */
class AppLockManager private constructor(application: Application) : Application.ActivityLifecycleCallbacks {
    // A device-local preference: restoring a collection must not restore a lock on another phone.
    private val file = AtomicFile(File(application.noBackupFilesDir, "biometric-lock"))
    private val session = AppLockSession(runCatching { file.openRead().use { it.read() == 1 } }.getOrDefault(false))
    private val _enabled = MutableStateFlow(session.enabled)
    val enabled = _enabled.asStateFlow()
    val locked get() = session.locked
    private val activities = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private val started = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private val resumed = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private val shields = WeakHashMap<Activity, View>()
    private val hiddenAccessibility = WeakHashMap<View, Int>()
    private val secured = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private var lockActivity = WeakReference<AppLockActivity>(null)
    private var launching = false

    init { application.registerActivityLifecycleCallbacks(this) }

    /** Called only after BiometricPrompt reports success while its activity is started. */
    internal fun authenticated(newEnabled: Boolean? = null) {
        if (newEnabled != null) {
            val stream = file.startWrite()
            try {
                stream.write(if (newEnabled) 1 else 0)
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
        }
        session.authenticated(newEnabled ?: session.enabled)
        _enabled.value = session.enabled
        shields.values.toList().forEach { (it.parent as? ViewGroup)?.removeView(it) }
        shields.clear()
        hiddenAccessibility.forEach { (view, value) -> view.importantForAccessibility = value }
        hiddenAccessibility.clear()
        activities.toList().forEach(::updateSecureWindow)
    }

    private fun updateSecureWindow(activity: Activity) {
        val protect = session.enabled && (locked || activity !in resumed)
        if (protect && activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0) {
            secured.add(activity)
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else if (!protect && secured.remove(activity)) {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun cover(activity: Activity) {
        if (activity is AppLockActivity || shields.containsKey(activity)) return
        val content = activity.findViewById<View>(android.R.id.content)
        if (content != null) {
            hiddenAccessibility[content] = content.importantForAccessibility
            content.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val shield = FrameLayout(activity).apply {
            setBackgroundColor(Color.rgb(17, 17, 17))
            isClickable = true
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "Vetro"
        }
        (activity.window.decorView as ViewGroup).addView(shield,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        shields[activity] = shield
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) {
        activities.add(activity)
        updateSecureWindow(activity)
        if (activity is AppLockActivity) {
            lockActivity = WeakReference(activity)
            launching = false
        } else if (locked) cover(activity)
    }

    override fun onActivityStarted(activity: Activity) {
        started.add(activity)
        updateSecureWindow(activity)
        if (locked) cover(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        resumed.add(activity)
        updateSecureWindow(activity)
        if (activity is AppLockActivity || !locked) return
        cover(activity)
        if (!launching && lockActivity.get()?.isFinishing != false) {
            launching = true
            try {
                activity.startActivity(Intent(activity, AppLockActivity::class.java))
            } catch (error: Exception) {
                launching = false
                throw error
            }
        }
    }

    override fun onActivityStopped(activity: Activity) {
        started.remove(activity)
        if (started.isEmpty() && !activity.isChangingConfigurations) {
            session.backgrounded()
            if (locked) activities.toList().forEach(::cover)
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        activities.remove(activity)
        started.remove(activity)
        resumed.remove(activity)
        shields.remove(activity)
        secured.remove(activity)
        if (lockActivity.get() === activity) lockActivity.clear()
    }
    override fun onActivityPaused(activity: Activity) {
        resumed.remove(activity)
        updateSecureWindow(activity)
    }
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit

    companion object {
        @Volatile private var instance: AppLockManager? = null
        fun get(context: Context): AppLockManager = instance ?: synchronized(this) {
            instance ?: AppLockManager(context.applicationContext as Application).also { instance = it }
        }
    }
}
