# ProGuard rules for the packaged desktop app (createReleaseDistributable). It only removes unused code:
# no renaming and no optimization passes, so stack traces stay readable and Kotlin keeps working.
# Compose Multiplatform adds its own rules for Compose and Skia.

# kotlinx.serialization: generated serializers are found through their companions.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.parchment.ai.**$$serializer { *; }
-keepclasseswithmembers class io.parchment.ai.** { kotlinx.serialization.KSerializer serializer(...); }

# JNA builds proxies for Library interfaces and reads Structure fields by reflection.
-keep class com.sun.jna.** { *; }
-keep interface * extends com.sun.jna.Library { *; }
-keep class * extends com.sun.jna.Structure { *; }

# ServiceLoader lookups: Ktor's engine (HttpClient {} names none) and the Swing main dispatcher.
-keep class * implements io.ktor.client.HttpClientEngineContainer { <init>(); }
-keep class kotlinx.coroutines.swing.SwingDispatcherFactory { *; }
-keep class kotlinx.coroutines.internal.MainDispatcherFactory { *; }

# The app's entry point.
-keep class io.parchment.ai.MainKt { public static void main(java.lang.String[]); }

# Optional classes referenced by libraries but never used here.
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**
-dontwarn com.oracle.svm.**
-dontwarn org.graalvm.**
-dontwarn okhttp3.internal.graal.**
-dontwarn android.**
-dontwarn kotlinx.coroutines.debug.**
-dontwarn io.ktor.util.debug.**
