package com.example.myapplication.security

/** Authentication is a process-local session, never a persisted unlock token. */
internal class AppLockSession(enabled: Boolean = false) {
    var enabled = enabled
        private set
    private var authenticated = false
    val locked: Boolean get() = enabled && !authenticated

    fun authenticated(enabled: Boolean = this.enabled) {
        this.enabled = enabled
        authenticated = true
    }

    fun backgrounded() {
        authenticated = false
    }
}
