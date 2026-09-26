import java.util.Properties

plugins {
    id("vetro.android.library.plain")
    id("vetro.kotlin.serialization")
    alias(libs.plugins.apollo)
}

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.phnem.vetro.network"
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        buildConfigField(
            "String",
            "TMDB_API_KEY",
            "\"${localProperties.getProperty("TMDB_API_KEY", "")}\""
        )
        // Известное ограничение: строка оказывается в APK как есть, извлекаема декомпиляцией.
        // Для лимитированного Kinopoisk Unofficial-ключа риск выше, чем у
        // бесплатного TMDB — принято сознательно на этой итерации (см.
        // .scratch/movie-series-infra/spec.md); серверный прокси — в бэклоге.
        buildConfigField(
            "String",
            "KINOPOISK_API_KEY",
            "\"${localProperties.getProperty("KINOPOISK_API_KEY", "")}\""
        )
        // Ключи API обогащения (Часть A, .scratch/sources-expansion). Пустой ключ — модуль молча
        // выключен. Как и у TMDB, строка попадает в APK: бесплатные ключи с лимитом, серверный прокси
        // — в бэклоге. Для Anime-Skip по умолчанию — общий client id из их документации.
        buildConfigField(
            "String",
            "OPENSUBTITLES_API_KEY",
            "\"${localProperties.getProperty("OPENSUBTITLES_API_KEY", "")}\""
        )
        buildConfigField(
            "String",
            "OMDB_API_KEY",
            "\"${localProperties.getProperty("OMDB_API_KEY", "")}\""
        )
        buildConfigField(
            "String",
            "YOUTUBE_API_KEY",
            "\"${localProperties.getProperty("YOUTUBE_API_KEY", "")}\""
        )
        buildConfigField(
            "String",
            "FANART_API_KEY",
            "\"${localProperties.getProperty("FANART_API_KEY", "")}\""
        )
        buildConfigField(
            "String",
            "NYT_API_KEY",
            "\"${localProperties.getProperty("NYT_API_KEY", "")}\""
        )
        buildConfigField(
            "String",
            "TASTEDIVE_API_KEY",
            "\"${localProperties.getProperty("TASTEDIVE_API_KEY", "")}\""
        )
        buildConfigField(
            "String",
            "GOOGLE_BOOKS_API_KEY",
            "\"${localProperties.getProperty("GOOGLE_BOOKS_API_KEY", "")}\""
        )
        buildConfigField(
            "String",
            "ANIME_SKIP_CLIENT_ID",
            "\"${localProperties.getProperty("ANIME_SKIP_CLIENT_ID", "ZGfO0sMF3eCwLYf8yMSCJjlynwNGRXWE")}\""
        )
    }
}

apollo {
    service("anilist") {
        packageName.set("com.example.myapplication.network.anilist")
        schemaFile.set(file("src/main/graphql/schema.json"))
    }
}

dependencies {
    api(platform(libs.ktor.bom))
    api(libs.ktor.client.core)
    api(libs.ktor.client.okhttp)
    api(libs.ktor.client.content.negotiation)
    api(libs.ktor.serialization.kotlinx.json)
    api(libs.kotlinx.serialization.json)
    api(libs.apollo.runtime)

    implementation(libs.ktor.client.logging)
    implementation(libs.okhttp)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)

    testImplementation(libs.junit)
    testImplementation("io.ktor:ktor-client-mock")
}
