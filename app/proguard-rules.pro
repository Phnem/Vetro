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
-keepclassmembers class com.example.myapplication.** {
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
