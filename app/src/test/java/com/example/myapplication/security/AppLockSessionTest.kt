package com.example.myapplication.security

import org.junit.Assert.*
import org.junit.Test

class AppLockSessionTest {
    @Test fun disabledByDefault() {
        val session = AppLockSession()
        assertFalse(session.enabled)
        assertFalse(session.locked)
        session.backgrounded()
        assertFalse(session.locked)
    }
    @Test fun enabledColdStartIsLocked() { assertTrue(AppLockSession(true).locked) }
    @Test fun authenticationUnlocksUntilTheAppLeavesTheForeground() {
        val session = AppLockSession(true)
        session.authenticated()
        assertFalse(session.locked)
        session.backgrounded()
        assertTrue(session.locked)
    }
    @Test fun enablingAfterConfirmationDoesNotImmediatelyPromptAgain() {
        val session = AppLockSession()
        session.authenticated(enabled = true)
        assertTrue(session.enabled)
        assertFalse(session.locked)
        session.backgrounded()
        assertTrue(session.locked)
    }
    @Test fun disablingAfterConfirmationSurvivesBackgrounding() {
        val session = AppLockSession(true)
        session.authenticated(enabled = false)
        session.backgrounded()
        assertFalse(session.enabled)
        assertFalse(session.locked)
    }
    @Test fun aNewProcessNeverRestoresAnAuthenticatedSession() {
        val old = AppLockSession(true)
        old.authenticated()
        assertFalse(old.locked)
        assertTrue(AppLockSession(old.enabled).locked)
    }
}
