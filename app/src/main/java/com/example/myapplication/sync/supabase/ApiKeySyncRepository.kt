package com.example.myapplication.sync.supabase

import com.example.myapplication.utils.runSuspendCatching
import android.util.Log
import com.example.myapplication.data.ai.AiCredentialsStore
import com.example.myapplication.data.ai.AiProvider
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * E2EE-синхронизация ключей AI Connect с Supabase (`user_api_keys`).
 *
 * Все ключи шифруются локально ([SyncCryptoManager]) ключом из [SyncPassphraseManager];
 * сервер хранит только шифротекст + IV. Удаление — tombstone (`deleted_at`), чтобы другие
 * устройства подхватили удаление при pull. Если пассфраза [PassphraseUnlock.Locked] —
 * синк ключей пропускается (данные не перетираются).
 */
class ApiKeySyncRepository(
    private val supabase: SupabaseClient,
    private val authRepository: AuthRepository,
    private val crypto: SyncCryptoManager,
    private val passphraseManager: SyncPassphraseManager,
    private val credentialsStore: AiCredentialsStore,
) {
    /** Полный цикл: подтянуть облачные ключи, затем выложить локальные. */
    suspend fun sync(): Result<Unit> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            val unlock = passphraseManager.ensureUnlocked()
            val key = when (unlock) {
                is PassphraseUnlock.Unlocked -> unlock.key
                PassphraseUnlock.Locked -> {
                    Log.i(TAG, "API key sync skipped: passphrase locked (recovery required)")
                    return@runSuspendCatching
                }
                PassphraseUnlock.NoUser -> return@runSuspendCatching
            }
            pullInternal(key)
            pushInternal(key)
        }.onFailure { Log.e(TAG, "API key sync failed: ${safeSyncError(it)}") }
    }

    /** Точечный push одного ключа (вызывается сразу после сохранения в AI Connect). */
    suspend fun pushKey(provider: AiProvider): Result<Unit> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            val userId = authRepository.currentUserId ?: return@runSuspendCatching
            val key = (passphraseManager.ensureUnlocked() as? PassphraseUnlock.Unlocked)?.key
                ?: return@runSuspendCatching
            val plaintext = credentialsStore.getApiKey(provider) ?: return@runSuspendCatching
            val blob = crypto.encrypt(key, plaintext)
            supabase.postgrest["user_api_keys"].upsert(
                RemoteApiKeyDto(
                    user_id = userId,
                    provider = provider.id,
                    ciphertext = blob.ciphertext,
                    iv = blob.iv,
                    updated_at = Instant.now().toString(),
                    deleted_at = null,
                )
            )
        }.onFailure { Log.w(TAG, "Push key ${provider.id} failed: ${safeSyncError(it)}") }
    }

    /** Точечное удаление ключа в облаке (tombstone). */
    suspend fun deleteKey(provider: AiProvider): Result<Unit> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            val userId = authRepository.currentUserId ?: return@runSuspendCatching
            supabase.postgrest["user_api_keys"].upsert(
                RemoteApiKeyDto(
                    user_id = userId,
                    provider = provider.id,
                    ciphertext = "",
                    iv = "",
                    updated_at = Instant.now().toString(),
                    deleted_at = Instant.now().toString(),
                )
            )
        }.onFailure { Log.w(TAG, "Delete key ${provider.id} failed: ${safeSyncError(it)}") }
    }

    private suspend fun pullInternal(key: javax.crypto.SecretKey) {
        val userId = authRepository.currentUserId ?: return
        val rows = supabase.postgrest["user_api_keys"]
            .select { filter { eq("user_id", userId) } }
            .decodeList<RemoteApiKeyDto>()
        for (row in rows) {
            val provider = AiProvider.fromId(row.provider) ?: continue
            if (row.deleted_at != null) {
                credentialsStore.clearApiKey(provider)
                continue
            }
            val plaintext = runCatching {
                crypto.decrypt(key, EncryptedBlob(ciphertext = row.ciphertext, iv = row.iv))
            }.getOrNull()
            if (!plaintext.isNullOrBlank() && credentialsStore.getApiKey(provider).isNullOrBlank()) {
                credentialsStore.saveApiKey(provider, plaintext)
            }
        }
    }

    private suspend fun pushInternal(key: javax.crypto.SecretKey) {
        val userId = authRepository.currentUserId ?: return
        val connected = credentialsStore.getAllConnectedProviders()
        if (connected.isEmpty()) return
        val now = Instant.now().toString()
        val dtos = connected.mapNotNull { provider ->
            val plaintext = credentialsStore.getApiKey(provider) ?: return@mapNotNull null
            val blob = crypto.encrypt(key, plaintext)
            RemoteApiKeyDto(
                user_id = userId,
                provider = provider.id,
                ciphertext = blob.ciphertext,
                iv = blob.iv,
                updated_at = now,
                deleted_at = null,
            )
        }
        if (dtos.isNotEmpty()) {
            supabase.postgrest["user_api_keys"].upsert(dtos)
        }
    }

    private companion object {
        const val TAG = "ApiKeySync"
    }
}
