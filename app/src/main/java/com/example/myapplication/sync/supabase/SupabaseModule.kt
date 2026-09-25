package com.example.myapplication.sync.supabase

import android.content.Context
import com.phnem.vetro.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.ExternalAuthAction
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.Github
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.logging.LogLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.android.ext.koin.androidContext
import kotlin.coroutines.cancellation.CancellationException

val supabaseModule = org.koin.dsl.module {
    single<SupabaseClient> {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        ) {
            // SDK error logs may include Realtime URLs with the apikey query parameter.
            defaultLogLevel = LogLevel.NONE
            install(Auth) {
                scheme = "vetrocollection"
                host = "auth-callback"
                defaultRedirectUrl = "vetrocollection://auth-callback"
                // Implicit flow puts tokens in URL fragment (#...) — Android drops it on deeplink.
                flowType = FlowType.PKCE
                defaultExternalAuthAction = ExternalAuthAction.CustomTabs()
            }
            install(Postgrest)
            install(Realtime)
            install(Storage)
            install(Functions)
        }
    }

    single {
        SupabaseSyncCoordinator(
            context = androidContext(),
            syncRepository = get(),
            authRepository = get(),
            supabase = get(),
            collectionImageRestoreCoordinator = get(),
            apiKeySyncRepository = get(),
            progressSyncRepository = get(),
            appScope = get(),
        )
    }

    single { AuthRepository(get(), get()) }
    single { SyncRepository(get(), get(), get(), get()) }
    single { CollectionImageRestoreCoordinator(get(), get(), get(), get(), get()) }
    single { AttachmentSyncManager(androidContext(), get(), get(), get()) }

    // AI Connect: E2EE-синхронизация ключей.
    single { SyncCryptoManager() }
    single { SyncPassphraseStore(androidContext()) }
    single { SyncPassphraseManager(get(), get(), get(), get()) }
    single { ApiKeySyncRepository(get(), get(), get(), get(), get()) }

    // Прогресс просмотра/чтения: DataStore ↔ Supabase.
    single { ProgressSyncRepository(androidContext(), get(), get(), get(), get(), get()) }
}

class AuthRepository(
    private val supabase: SupabaseClient,
    private val context: Context
) {
    private val prefs = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)

    val isUserSignedIn: Flow<Boolean> = supabase.auth.sessionStatus.map {
        it is io.github.jan.supabase.auth.status.SessionStatus.Authenticated
    }

    val linkedProvidersFlow: Flow<Set<String>> = supabase.auth.sessionStatus.map {
        linkedProviders()
    }
    
    val currentUserId: String?
        get() = supabase.auth.currentUserOrNull()?.id

    var isGuest: Boolean
        get() = prefs.getBoolean("is_guest", false)
        set(value) = prefs.edit().putBoolean("is_guest", value).apply()

    val currentUserEmail: String?
        get() = supabase.auth.currentUserOrNull()?.email

    fun linkedProviders(): Set<String> {
        return supabase.auth.currentIdentitiesOrNull()
            ?.map { it.provider }
            ?.toSet()
            ?: emptySet()
    }

    fun hasToken(): Boolean {
        return supabase.auth.sessionStatus.value is io.github.jan.supabase.auth.status.SessionStatus.Authenticated
    }

    fun signInAsGuest() {
        isGuest = true
    }

    suspend fun signInWithGoogle(@Suppress("UNUSED_PARAMETER") activityContext: Context): Result<Unit> {
        return try {
            supabase.auth.signInWith(Google, redirectUrl = "vetrocollection://auth-callback") {}
            isGuest = false
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("AuthRepository", "signInWithGoogle failed: ${safeSyncError(e)}")
            Result.failure(safeSyncFailure(e))
        }
    }

    suspend fun signInWithGithub(): Result<Unit> {
        return try {
            supabase.auth.signInWith(Github, redirectUrl = "vetrocollection://auth-callback") {}
            isGuest = false
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("AuthRepository", "signInWithGithub failed: ${safeSyncError(e)}")
            Result.failure(safeSyncFailure(e))
        }
    }

    suspend fun linkGoogle(): Result<Unit> {
        return try {
            supabase.auth.linkIdentity(Google, redirectUrl = "vetrocollection://auth-callback") {}
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("AuthRepository", "linkGoogle failed: ${safeSyncError(e)}")
            Result.failure(safeSyncFailure(e))
        }
    }

    suspend fun linkGithub(): Result<Unit> {
        return try {
            supabase.auth.linkIdentity(Github, redirectUrl = "vetrocollection://auth-callback") {}
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("AuthRepository", "linkGithub failed: ${safeSyncError(e)}")
            Result.failure(safeSyncFailure(e))
        }
    }

    suspend fun signInWithEmail(email: String, password: String): Result<Unit> {
        return try {
            supabase.auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
            isGuest = false
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(safeSyncFailure(e))
        }
    }

    suspend fun signUpWithEmail(email: String, password: String): Result<Unit> {
        return try {
            supabase.auth.signUpWith(Email) {
                this.email = email
                this.password = password
            }
            isGuest = false
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(safeSyncFailure(e))
        }
    }

    suspend fun resetPasswordForEmail(email: String): Result<Unit> {
        return try {
            supabase.auth.resetPasswordForEmail(
                email = email.trim(),
                redirectUrl = "vetrocollection://auth-callback",
                captchaToken = "",
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(safeSyncFailure(e))
        }
    }

    suspend fun setPassword(newPassword: String): Result<Unit> {
        return try {
            supabase.auth.updateUser {
                password = newPassword
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(safeSyncFailure(e))
        }
    }

    suspend fun signOut() {
        try {
            isGuest = false
            supabase.auth.signOut()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("AuthRepository", "signOut failed: ${safeSyncError(e)}")
        }
    }
}
