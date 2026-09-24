package com.example.myapplication.domain.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppReleaseVersionComparerTest {

    @Test
    fun same_core_beta_vs_plain_no_banner() {
        assertFalse(AppReleaseVersionComparer.isRemoteSemanticallyNewer("3.2.7", "v3.2.7-Beta"))
    }

    @Test
    fun same_core_debug_vs_beta_no_banner() {
        assertFalse(AppReleaseVersionComparer.isRemoteSemanticallyNewer("3.2.7-debug", "v3.2.7-Beta"))
    }

    @Test
    fun newer_patch_returns_true() {
        assertTrue(AppReleaseVersionComparer.isRemoteSemanticallyNewer("v3.2.5", "3.2.7"))
        assertTrue(AppReleaseVersionComparer.isRemoteSemanticallyNewer("3.2.5", "v3.2.7-Beta"))
    }

    @Test
    fun equal_release_no_banner() {
        assertFalse(AppReleaseVersionComparer.isRemoteSemanticallyNewer("3.2.7", "v3.2.7"))
        assertFalse(AppReleaseVersionComparer.isRemoteSemanticallyNewer("v3.2.7", "v3.2.7"))
    }

    @Test
    fun local_newer_than_remote_no_banner() {
        assertFalse(AppReleaseVersionComparer.isRemoteSemanticallyNewer("v3.2.8", "3.2.7"))
    }

    @Test
    fun same_triple_dual_prerelease_no_banner() {
        assertFalse(AppReleaseVersionComparer.isRemoteSemanticallyNewer("1.0.0-beta.1", "v1.0.0-rc.1"))
    }

    @Test
    fun uppercase_v_prefix_parses_like_lowercase() {
        assertFalse(AppReleaseVersionComparer.isRemoteSemanticallyNewer("V3.3.4-Beta", "v3.3.4-Beta"))
        assertFalse(AppReleaseVersionComparer.isRemoteSemanticallyNewer("V3.3.4-Beta", "v3.3.3-Alpha"))
        assertTrue(AppReleaseVersionComparer.isRemoteSemanticallyNewer("V3.3.4-Beta", "v3.3.5-Beta"))
    }
}
