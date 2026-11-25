package com.example.myapplication.sync.supabase

import com.example.myapplication.utils.runSuspendCatching
import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.crypto.SecretKey

/**
 * Управляет E2EE-пассфразой синхронизации ключей AI Connect.
 *
 * Bootstrap (первое устройство): генерирует пассфразу + соль, выводит AES-ключ, пушит
 * {salt, verifier} в `user_sync_passphrase`. Другие устройства сравнивают локальную фразу
 * с облачным verifier; если фразы нет/не совпала — состояние [PassphraseUnlock.Locked],
 * и пользователь вводит recovery-фразу через [unlockWithRecoveryPhrase].
 *
 * Выведенный [SecretKey] кэшируется на время сессии (по userId).
 */
class SyncPassphraseManager(
    private val supabase: SupabaseClient,
    private val authRepository: AuthRepository,
    private val crypto: SyncCryptoManager,
    private val store: SyncPassphraseStore,
) {
    private val mutex = Mutex()
    private var cachedUserId: String? = null
    private var cachedKey: SecretKey? = null

    suspend fun ensureUnlocked(): PassphraseUnlock = withContext(Dispatchers.IO) {
        val userId = authRepository.currentUserId
        if (userId == null || authRepository.isGuest) return@withContext PassphraseUnlock.NoUser
        mutex.withLock {
            cachedKey?.let { if (cachedUserId == userId) return@withLock PassphraseUnlock.Unlocked(it) }
            val remote = fetchRemote(userId)
            if (remote == null) bootstrap(userId) else unlockFromRemote(userId, remote)
        }
    }

    /** Ввод recovery-фразы на новом устройстве: проверка по облачному verifier и локальное сохранение. */
    suspend fun unlockWithRecoveryPhrase(phrase: String): Boolean = withContext(Dispatchers.IO) {
        val userId = authRepository.currentUserId ?: return@withContext false
        val candidate = phrase.trim()
        if (candidate.isEmpty()) return@withContext false
        mutex.withLock {
            val remote = fetchRemote(userId) ?: return@withLock false
            val key = crypto.deriveKey(candidate, remote.salt)
            if (crypto.verifier(key) != remote.verifier_hash) return@withLock false
            store.savePassphrase(candidate)
            cachedUserId = userId
            cachedKey = key
            true
        }
    }

    fun forget() {
        cachedKey = null
        cachedUserId = null
    }

    private suspend fun bootstrap(userId: String): PassphraseUnlock {
        val passphrase = store.getPassphrase() ?: crypto.generatePassphrase().also { store.savePassphrase(it) }
        val salt = crypto.generateSalt()
        val key = crypto.deriveKey(passphrase, salt)
        return try {
            pushRemote(
                SyncPassphraseDto(
                    user_id = userId,
                    salt = salt,
                    verifier_hash = crypto.verifier(key),
                    updated_at = Instant.now().toString(),
                )
            )
            cachedUserId = userId
            cachedKey = key
            PassphraseUnlock.Unlocked(key)
        } catch (e: Exception) {
            Log.e(TAG, "Passphrase bootstrap failed: ${safeSyncError(e)}")
            PassphraseUnlock.Locked
        }
    }

    private fun unlockFromRemote(userId: String, remote: SyncPassphraseDto): PassphraseUnlock {
        val local = store.getPassphrase() ?: return PassphraseUnlock.Locked
        val key = crypto.deriveKey(local, remote.salt)
        return if (crypto.verifier(key) == remote.verifier_hash) {
            cachedUserId = userId
            cachedKey = key
            PassphraseUnlock.Unlocked(key)
        } else {
            PassphraseUnlock.Locked
        }
    }

    private suspend fun fetchRemote(userId: String): SyncPassphraseDto? =
        runSuspendCatching {
            supabase.postgrest["user_sync_passphrase"]
                .select { filter { eq("user_id", userId) } }
                .decodeList<SyncPassphraseDto>()
                .firstOrNull()
        }.getOrElse {
            Log.w(TAG, "Fetch passphrase failed: ${safeSyncError(it)}")
            null
        }

    private suspend fun pushRemote(dto: SyncPassphraseDto) {
        supabase.postgrest["user_sync_passphrase"].upsert(dto)
    }

    private companion object {
        const val TAG = "SyncPassphrase"
    }
}
