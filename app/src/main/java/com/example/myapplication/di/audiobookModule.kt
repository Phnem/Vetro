package com.example.myapplication.di

import com.example.myapplication.audiobooks.AudiobookFeatureGate
import com.example.myapplication.audiobooks.data.local.LocalFolderSource
import com.example.myapplication.audiobooks.domain.source.ManifestResolver
import com.example.myapplication.audiobooks.domain.source.ManifestSource
import com.phnem.vetro.BuildConfig
import org.koin.dsl.module

/** Audiobook registrations live here as the feature is built ticket by ticket. */
val audiobookModule = module {
    single { AudiobookFeatureGate(enabled = BuildConfig.AUDIOBOOKS_ENABLED) }
    single { LocalFolderSource(get()) }
    single<ManifestSource> { get<LocalFolderSource>() }
    single { ManifestResolver(sources = getAll<ManifestSource>()) }
}
