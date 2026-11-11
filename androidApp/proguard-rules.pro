# R8 rules for the Android release build. Libraries ship their own rules (kotlinx.serialization,
# coroutines, OkHttp, Compose); these cover what they leave to the app.

# Ktor finds its engine through ServiceLoader because AiProviderEngine builds HttpClient {} without one.
-keep class * implements io.ktor.client.HttpClientEngineContainer { <init>(); }
-keepnames class io.ktor.client.engine.okhttp.** { *; }

# JVM-only classes that Ktor, OkHttp and coroutines reference but never use on Android.
-dontwarn java.lang.management.**
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.sun.jna.**

# Keep source file names and line numbers so crash reports stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
