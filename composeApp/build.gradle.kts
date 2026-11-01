import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    sourceSets {
        val desktopMain by getting

        commonMain.dependencies {
            // Pure Compose Foundation & UI primitives only — zero Material / Fluent bloat
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.components.resources)

            // Coroutines & JSON Serialization
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)

            // Ktor HTTP/2 & SSE Streaming Engine
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }

        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.okhttp)
        }

        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.ktor.client.okhttp)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "io.parchment.ai"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.parchment.ai"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        // Allows GitHub Actions or local builds to sign release APKs via env vars or debug keystore fallback
        getByName("debug") {
            // Standard Android debug keystore
        }
        create("releaseSigning") {
            val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank() && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: "parchment123"
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: "parchment"
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: "parchment123"
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
            signingConfig = if (!keystorePath.isNullOrBlank() && file(keystorePath).exists()) {
                signingConfigs.getByName("releaseSigning")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.desktop {
    application {
        mainClass = "io.parchment.ai.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "Parchment"
            packageVersion = "1.0.0"
            description = "Parchment — Minimal Bleeding-Edge Literary & Agent AI Frontend"
            vendor = "Parchment"

            // Minimal JDK runtime modules for small footprint
            modules(
                "java.base",
                "java.desktop",
                "java.net.http",
                "jdk.crypto.ec",
                "java.naming",
                "jdk.unsupported"
            )

            windows {
                menuGroup = "Parchment"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "8d49c66a-9870-4c97-9499-684f84a8e432"
            }
        }
    }
}
