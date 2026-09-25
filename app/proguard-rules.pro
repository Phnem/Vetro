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

-keep class kotlin.Metadata { *; }
-dontwarn org.jetbrains.annotations.**

-keepclassmembers class **$$serializer { *; }
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep,includedescriptorclasses class com.example.myapplication.**$$serializer { *; }

-keep class org.koin.** { *; }
-keep class * extends org.koin.core.module.Module { *; }

-keepnames class androidx.compose.** { *; }
-keep class app.cash.sqldelight.** { *; }
-keep class com.example.myapplication.data.local.** { *; }

-keep class io.ktor.** { *; }
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.internal.platform.**

-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

-keepnames class com.apollographql.apollo.** { *; }

# Release: вырезать Log.d/Log.v — это отладочный шум: работа на каждом вызове и утечка
# URL/параметров в logcat. Log.i/w/e остаются: по ним разбираются отчёты с устройств.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
