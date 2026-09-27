package com.example.myapplication.media.source

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAccountSettingsTest {

    private class Store : PlaybackSourceConfigStore {
        val accounts = mutableMapOf<UserAccountKind, UserAccountConfig>()
        override fun webDav(): WebDavConfig? = null
        override fun saveWebDav(config: WebDavConfig) = Unit
        override fun clearWebDav() = Unit
        override fun personalServer(provider: PersonalMediaServerProvider): PersonalMediaServerConfig? = null
        override fun savePersonalServer(provider: PersonalMediaServerProvider, config: PersonalMediaServerConfig) = Unit
        override fun clearPersonalServer(provider: PersonalMediaServerProvider) = Unit
        override fun account(kind: UserAccountKind) = accounts[kind]
        override fun saveAccount(kind: UserAccountKind, config: UserAccountConfig) { accounts[kind] = config }
        override fun clearAccount(kind: UserAccountKind) { accounts.remove(kind) }
    }

    private class Tester : PlaybackSourceConnectionTester {
        var tested: UserAccountConfig? = null
        override suspend fun testWebDav(config: WebDavConfig) = false
        override suspend fun testPersonalServer(provider: PersonalMediaServerProvider, config: PersonalMediaServerConfig) = false
        override suspend fun testAccount(kind: UserAccountKind, config: UserAccountConfig): Boolean { tested = config; return true }
    }

    private val draft = PlaybackSourcePublicDraft(PlaybackSourceKind.OPENSUBTITLES, username = "me")

    @Test
    fun `opensubtitles is BYOK - the row is always there and needs the user's key`() {
        val store = Store()
        val service = DefaultPlaybackSourceSettingsService(store, Tester())
        assertTrue(service.summaries().any { it.kind == PlaybackSourceKind.OPENSUBTITLES && !it.configured })
        assertFalse("без ключа API учётка не сохраняется", service.save(draft, "pw"))
        assertTrue(service.save(draft, "pw", replacementApiKey = " key-1 "))
        assertEquals("key-1", store.accounts[UserAccountKind.OPENSUBTITLES]?.apiKey)
        val shown = service.draft(PlaybackSourceKind.OPENSUBTITLES)
        assertTrue(shown.hasStoredApiKey)
        assertTrue(shown.hasStoredSecret)
    }

    @Test
    fun `stored key and password are reused only for the same login`() = runBlocking {
        val store = Store()
        val tester = Tester()
        val service = DefaultPlaybackSourceSettingsService(store, tester)
        service.save(draft, "pw", replacementApiKey = "key-1")
        assertTrue(service.test(draft, "", "") == true)
        assertEquals("key-1", tester.tested?.apiKey)
        assertEquals("pw", tester.tested?.password)
        // Другой логин — сохранённые пароль и ключ к нему не переносятся.
        assertFalse(service.save(draft.copy(username = "someone"), ""))
        assertTrue(service.test(draft.copy(username = "someone"), "", "") == null)
    }
}
