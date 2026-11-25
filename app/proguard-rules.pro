# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-keepattributes SourceFile,LineNumberTable
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

-dontwarn org.jetbrains.annotations.**

# kotlinx.serialization ships its own R8 rules; keep only what the plugin generates for our
# classes (lookup by name happens for companion serializer() and $$serializer objects).
-keep,includedescriptorclasses class com.example.myapplication.**$$serializer { *; }
# Только у сериализуемых: правило на все классы приложения не давало R8 убирать их companion.
-keepclassmembers @kotlinx.serialization.Serializable class com.example.myapplication.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.myapplication.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Koin, Compose, SQLDelight, Ktor, OkHttp, Apollo, Media3, Coil and WorkManager bring consumer
# rules with the AAR. The blanket -keep of their whole packages (and of our data.local) that
# used to sit here only disabled shrinking and obfuscation for code nobody reflects on.
-dontwarn okhttp3.internal.platform.**

-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# Release: вырезать Log.d/Log.v — это отладочный шум: работа на каждом вызове и утечка
# URL/параметров в logcat. Log.i/w/e остаются: по ним разбираются отчёты с устройств.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# libtorrent4j (торрент-источники аудиокниг): SWIG/JNI находит классы и методы по именам.
-keep class org.libtorrent4j.swig.** { *; }
-keep class org.libtorrent4j.** { *; }

# --- Mihon extensions (manga/extensions) ---
# Extension APKs are compiled against these classes (compileOnly stubs) and resolve them by name
# from Vetro's classloader at runtime, so R8 must neither rename nor strip their public API.
# Same set Mihon keeps; costs some APK size, without it every extension dies with NoSuchMethodError.
# API, от которого наследуются расширения, без allowoptimization: иначе R8 помечает final методы, которые в самом
# приложении никто не переопределяет (fetchRelatedMangaList, getFilterList), и расширение не загружается:
# LinkageError «overrides final method».
# Все члены, включая приватные: расширения берут их рефлексией (MangaDex — HttpSource.headers$delegate).
-keep class eu.kanade.tachiyomi.** { *; }
# Внутренности kotlin-reflect (protobuf и разбор метаданных) расширения напрямую не зовут,
# а общее правило удерживало их целиком.
-keep,allowoptimization class !kotlin.reflect.jvm.internal.**,kotlin.** { public protected *; }
-keep,allowoptimization class kotlinx.coroutines.** { public protected *; }
-keep,allowoptimization class kotlinx.serialization.** { public protected *; }
-keep,allowoptimization class okhttp3.** { public protected *; }
-keep,allowoptimization class okio.** { public protected *; }
-keep,allowoptimization class org.jsoup.** { public protected *; }
-keep,allowoptimization class uy.kohesive.injekt.** { public protected *; }
# fullType<T>() читает T из обобщённой сигнатуры анонимного наследника (ExtensionRuntime), а R8 в full mode
# сохраняет сигнатуры только у классов, названных правилом. Без этого расширения не устанавливаются:
# «TypeReference constructed without actual type information».
-keep,allowobfuscation,allowshrinking,allowoptimization class * extends uy.kohesive.injekt.api.FullTypeReference
-keep,allowoptimization class androidx.preference.** { public protected *; }
-keep,allowoptimization class com.squareup.zstd.** { public protected *; }
-dontwarn uy.kohesive.injekt.**
-dontwarn org.slf4j.**
