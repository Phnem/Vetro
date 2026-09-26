import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.apollo)
    alias(libs.plugins.baselineprofile)
}

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun oauthProp(name: String): String =
    System.getenv(name)
        ?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty(name, "")

// Device smoke uses one stable side-by-side package and never replaces the user's Vetro install.
val audiobookSmokeBuild = providers.gradleProperty("audiobookSmokeBuild").orNull == "true"

sqldelight {
    databases {
        create("AnimeDatabase") {
            packageName.set("com.example.myapplication.data.local")
        }
    }
}

android {
    namespace = "com.phnem.vetro"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.phnem.vetro"
        minSdk = 26
        targetSdk = 36
        versionCode = 335
        versionName = "v3.3.5-Beta"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GITHUB_OWNER", "\"Phnem\"")
        buildConfigField("String", "GITHUB_REPO", "\"Vetro-Collection\"")
        buildConfigField("boolean", "AUDIOBOOKS_ENABLED", "true")
        buildConfigField(
            "String",
            "SHIKIMORI_CLIENT_ID",
            "\"${oauthProp("SHIKIMORI_CLIENT_ID")}\""
        )
        buildConfigField(
            "String",
            "SHIKIMORI_CLIENT_SECRET",
            "\"${oauthProp("SHIKIMORI_CLIENT_SECRET")}\""
        )
        buildConfigField(
            "String",
            "SHIKIMORI_REDIRECT_URI",
            "\"${oauthProp("SHIKIMORI_REDIRECT_URI")}\""
        )
        buildConfigField(
            "String",
            "MAL_CLIENT_ID",
            "\"${oauthProp("MAL_CLIENT_ID")}\""
        )
        buildConfigField(
            "String",
            "MAL_CLIENT_SECRET",
            "\"${oauthProp("MAL_CLIENT_SECRET")}\""
        )
        buildConfigField(
            "String",
            "MAL_REDIRECT_URI",
            "\"${oauthProp("MAL_REDIRECT_URI")}\""
        )
        buildConfigField(
            "String",
            "ANILIST_CLIENT_ID",
            "\"${oauthProp("ANILIST_CLIENT_ID")}\""
        )
        buildConfigField(
            "String",
            "ANILIST_CLIENT_SECRET",
            "\"${oauthProp("ANILIST_CLIENT_SECRET")}\""
        )
        buildConfigField(
            "String",
            "ANILIST_REDIRECT_URI",
            "\"${oauthProp("ANILIST_REDIRECT_URI")}\""
        )
        buildConfigField(
            "String",
            "SUPABASE_URL",
            "\"${oauthProp("SUPABASE_URL")}\""
        )
        buildConfigField(
            "String",
            "SUPABASE_ANON_KEY",
            "\"${oauthProp("SUPABASE_ANON_KEY")}\""
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildTypes {
        debug {
            if (audiobookSmokeBuild) applicationIdSuffix = ".ab07smoke"
        }
        release {
            if (audiobookSmokeBuild) {
                applicationIdSuffix = ".ab07smoke"
                signingConfig = signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        jniLibs {
            // Распаковка .so при установке была нужна только бинарнику ffmpeg (его запускали как
            // исполняемый файл). Без него библиотеки грузятся прямо из APK: меньше места после
            // установки, выравнивание под 16 КБ страницы AGP делает сам.
            useLegacyPackaging = false
        }
    }
}

kotlin {
    jvmToolchain(21)

    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.addAll(
            "-Xcontext-receivers",
        )
    }
}

configurations.all {
    resolutionStrategy {
        // Some libraries may bring Kotlin stdlib 2.x with a higher version.
        // This project uses Kotlin 2.1.0, so we force stdlib artifacts to match it.
        force("org.jetbrains.kotlin:kotlin-stdlib:2.1.0")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.1.0")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.1.0")
    }
}

dependencies {
    // 1. Compose: строго через BOM
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.animation)
    debugImplementation(libs.compose.ui.tooling)

    // 2. Koin 4: строго через BOM
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // 3. Coil 3
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // 3b. Local player (isolated feature: com.example.myapplication.localplayer)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)
    implementation(libs.media3.ui.compose)
    implementation(libs.media3.session)
    implementation(libs.media3.inspector)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.exoplayer.workmanager)
    implementation(libs.jsoup)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.palette)

    // 4. Навигация, UI, Стейт
    implementation(libs.navigation.compose)
    implementation(libs.activity.compose)
    implementation(libs.datastore.preferences)
    // Ставит baseline profile (src/release/generated/baselineProfiles) при установке и первом
    // запуске: горячие пути старта и прокрутки компилируются заранее, а не интерпретируются.
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
    // Только ради версии нативной библиотеки (16 КБ страницы) — см. комментарий в каталоге.
    implementation(libs.androidx.graphics.path)
    implementation(libs.androidx.security.crypto)
    implementation(libs.tink.android)
    implementation(libs.backdrop)

    // 5. AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // 6. Supabase
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.auth)
    implementation(libs.supabase.realtime)
    implementation(libs.supabase.storage)
    implementation(libs.supabase.functions)

    // 7. OkHttp, WorkManager
    implementation(libs.okhttp)
    // Торрент-источники аудиокниг: движок libtorrent (Java-обёртка + нативные библиотеки телефонов;
    // x86_64 — только для эмулятора в debug, чтобы не раздувать релиз).
    implementation(libs.libtorrent4j)
    implementation(libs.libtorrent4j.android.arm64)
    implementation(libs.libtorrent4j.android.arm)
    debugImplementation(libs.libtorrent4j.android.x64)
    implementation(libs.work.runtime.ktx)

    // 8. SQLDelight
    implementation(libs.sqldelight.android.driver)
    implementation(libs.sqldelight.coroutines)

    // 9. Kotlin Serialization + Immutable collections (Zero Jank)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.collections.immutable)

    // 11. Apollo (core:network; по умолчанию Apollo 4 использует OkHttp на Android)
    implementation(project(":core:network"))
    implementation(libs.apollo.runtime)

    // 12. Markdown renderer (GitHub changelog)
    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.markdown.renderer.coil3)

    // Test
    testImplementation(libs.junit)
    testImplementation("io.ktor:ktor-client-mock")
    // JVM SQLite driver — прогон SQLDelight-миграций в JUnit-тестах (не android-driver, тому
    // нужен Android runtime).
    testImplementation(libs.sqldelight.sqlite.driver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.fastlane.screengrab)
    debugImplementation(libs.androidx.ui.test.manifest)
}
