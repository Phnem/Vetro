package com.example.myapplication.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.example.myapplication.data.ai.AiCredentialsStore
import com.example.myapplication.data.local.AnimeLocalDataSource
import com.example.myapplication.data.local.DeveloperMirrorCoordinator
import com.example.myapplication.data.local.EncryptedGeminiApiKeyRepository
import com.example.myapplication.data.local.ImageCompressionMigrator
import com.example.myapplication.data.local.ImageStorageRepositoryImpl
import com.example.myapplication.data.local.LegacyCollectionSafMigrator
import com.example.myapplication.data.local.LegacyStorageMigrator
import com.example.myapplication.data.local.MigrationManager
import com.example.myapplication.data.local.SQLDelightDatabaseFactory
import com.example.myapplication.data.local.VetroPublicDbExporter
import com.example.myapplication.data.local.VetroStoragePaths
import com.example.myapplication.data.repository.GeminiApiKeyRepository
import com.example.myapplication.data.repository.ImageStorageRepository
import com.example.myapplication.domain.IdGenerator
import com.example.myapplication.domain.RealIdGenerator
import com.example.myapplication.domain.addedit.GetAnimeForEditUseCase
import com.example.myapplication.domain.addedit.SaveAnimeUseCase
import com.example.myapplication.domain.addedit.UpdateCommentUseCase
import com.example.myapplication.sync.ExternalListSyncCoordinator
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

private val Context.migrationDataStore: DataStore<Preferences> by preferencesDataStore(name = "migration_prefs")
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings_prefs")

val databaseModule = module {
    single { VetroStoragePaths(androidContext()) }
    single { SQLDelightDatabaseFactory(androidContext()) }
    single { get<SQLDelightDatabaseFactory>().getDatabase() }
    single { VetroPublicDbExporter(androidContext(), get(), get()) }
    single { DeveloperMirrorCoordinator(settingsDataStore = get(named("settings")), exporter = get()) }
    single { AnimeLocalDataSource(get(), get(), get()) }
    single<ImageStorageRepository> {
        ImageStorageRepositoryImpl(
            context = androidContext(),
            storagePaths = get(),
            httpClient = get(),
        )
    }
    single<IdGenerator> { RealIdGenerator() }
    single { GetAnimeForEditUseCase(get()) }
    single { SaveAnimeUseCase(get(), get(), get(), get(named("settings")), get()) }
    single { UpdateCommentUseCase(get()) }
    single<GeminiApiKeyRepository> {
        EncryptedGeminiApiKeyRepository(context = androidContext())
    }
    single { AiCredentialsStore(context = androidContext()) }

    single<DataStore<Preferences>>(named("migration")) {
        androidContext().migrationDataStore
    }
    single<DataStore<Preferences>>(named("settings")) {
        androidContext().settingsDataStore
    }
    single {
        LegacyStorageMigrator(
            context = androidContext(),
            storagePaths = get(),
            dataStore = get(named("migration")),
        )
    }
    single {
        LegacyCollectionSafMigrator(
            context = androidContext(),
            storagePaths = get(),
            localDataSource = get(),
            imageStorage = get(),
            dataStore = get(named("migration")),
        )
    }
    single {
        MigrationManager(
            context = androidContext(),
            localDataSource = get(),
            dataStore = get(named("migration")),
            storagePaths = get(),
        )
    }
    
    single {
        ImageCompressionMigrator(
            db = get(),
            storagePaths = get()
        )
    }
    single {
        com.example.myapplication.data.local.StartupSweeps(
            dataStore = get(named("migration")),
            legacyStorageMigrator = get(),
            legacyCollectionSafMigrator = get(),
            imageCompressionMigrator = get(),
            appScope = get(),
        )
    }

    single {
        ExternalListSyncCoordinator(
            context = androidContext(),
            animeLocalDataSource = get(),
            animeRepository = get(),
            addFromApiUseCase = get(),
            shikiRateLimiter = get(named("api_rate_burst")),
            aniListRemoteDataSource = get()
        )
    }
}
