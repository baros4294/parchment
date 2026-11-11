import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

val appVersionProp = (project.findProperty("appVersion") as? String)?.removePrefix("v")?.trim()
    ?.takeIf { it.isNotEmpty() } ?: "2.0.0"
val appVersionCodeProp = (project.findProperty("appVersionCode") as? String)?.toIntOrNull() ?: 200
val githubRepoProp = (project.findProperty("githubRepo") as? String)?.trim()
    ?.takeIf { it.isNotEmpty() } ?: "baros4294/parchment"
// Set only by .github/workflows/build-installers.yml. Dev runs and local packages must never install an update over the real app.
val officialReleaseProp = (project.findProperty("officialRelease") as? String)?.trim().toBoolean()

val generateBuildConfig by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/parchmentConfig")
    inputs.property("appVersion", appVersionProp)
    inputs.property("appVersionCode", appVersionCodeProp)
    inputs.property("githubRepo", githubRepoProp)
    inputs.property("officialRelease", officialReleaseProp)
    outputs.dir(outputDir)
    val ver = appVersionProp
    val code = appVersionCodeProp
    val repo = githubRepoProp
    val official = officialReleaseProp
    doLast {
        val file = outputDir.get().file("io/parchment/ai/ParchmentBuildConfig.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package io.parchment.ai

            object ParchmentBuildConfig {
                const val APP_VERSION = "$ver"
                const val APP_VERSION_CODE = $code
                const val DEFAULT_GITHUB_REPO = "$repo"
                /** True only for builds made by the release workflow; other builds never install updates. */
                const val OFFICIAL_RELEASE = $official
            }
            """.trimIndent()
        )
    }
}

val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
    ?: System.getenv("ANDROID_KEYSTORE_PASSWORD")

val validatePrivateReleaseSigner by tasks.registering {
    doLast {
        val location = System.getenv("ANDROID_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
            ?: error("Release signing requires ANDROID_KEYSTORE_PATH; load the private signer first.")
        val password = System.getenv("ANDROID_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
            ?: error("Release signing requires ANDROID_KEYSTORE_PASSWORD.")
        val alias = System.getenv("ANDROID_KEY_ALIAS")?.takeIf { it.isNotBlank() }
            ?: error("Release signing requires ANDROID_KEY_ALIAS.")
        val store = KeyStore.getInstance(file(location), password.toCharArray())
        require(store.isKeyEntry(alias)) { "Release signer alias must contain a private key." }
        val certificate = store.getCertificate(alias) ?: error("Release signer certificate is unavailable.")
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        require(fingerprint != "ed96a0a733e9e6d38633f04a6a182c997fe60d480d9e33e5f078faa2536d26eb") {
            "The exposed repository key cannot sign a release. Use the private signer and migration plan."
        }
        val keyPassword = requireNotNull(releaseKeyPassword).toCharArray()
        try {
            require(store.getKey(alias, keyPassword) is PrivateKey) {
                "Release signer alias must contain a readable private key."
            }
        } finally {
            keyPassword.fill('\u0000')
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" || it.name == "validateSigningRelease" }
    .configureEach { dependsOn(validatePrivateReleaseSigner) }

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
        val androidMain by getting
        desktopMain.kotlin.srcDir("src/jvmShared/kotlin")

        commonMain {
            kotlin.srcDir(generateBuildConfig.map { it.outputs.files.singleFile })
            dependencies {
            // Pure Compose Foundation & UI primitives only — zero Material / Fluent bloat
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)

            // Coroutines & JSON Serialization
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)

            // Ktor HTTP/2 & SSE Streaming Engine
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            }
        }

        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.okhttp)
        }
        androidMain.kotlin.srcDir("src/jvmShared/kotlin")

        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.jna)
            implementation(libs.ktor.client.okhttp)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// The UI gallery renders every screen to PNG; verify.py passes -Pparchment.gallery=true only when it is wanted.
val renderGallery = providers.gradleProperty("parchment.gallery").orElse("false")
tasks.withType<Test>().configureEach {
    systemProperty("parchment.gallery", renderGallery.get())
}

android {
    namespace = "io.parchment.ai"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    sourceSets["main"].assets.srcDirs("src/commonMain/resources")

    defaultConfig {
        applicationId = "io.parchment.ai"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = appVersionCodeProp
        versionName = appVersionProp
    }

    signingConfigs {
        // Debug uses the developer's normal local debug key. Release has no repository-key fallback.
        create("releaseSigning") {
            val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            // R8 removes unused code and resources: a smaller APK that starts faster on low-end phones.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("releaseSigning")
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

        // Lean JVM ergonomics & automatic OS RAM page uncommit:
        // - Starts at 32 MB and aggressively uncommits freed pages back to Windows every 10s (so idle RAM stays ~130-190 MB),
        // - Allows up to 1024 MB (-Xmx1024m) ceiling so massive 100-step agent tool turns & multi-MB attachments NEVER hit OutOfMemoryError!
        jvmArgs(
            "-Xms32m",
            "-Xmx1024m",
            "-XX:+UseG1GC",
            "-XX:+UseStringDeduplication",
            "-XX:MinHeapFreeRatio=10",
            "-XX:MaxHeapFreeRatio=20",
            "-XX:-ShrinkHeapInSteps",
            "-XX:G1PeriodicGCInterval=8000",
            "-XX:+G1PeriodicGCInvokesConcurrent",
            "-XX:SoftRefLRUPolicyMSPerMB=250"
        )

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "Parchment"
            packageVersion = appVersionProp
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
                iconFile.set(rootProject.file("installer/parchment-icon.ico"))
                menuGroup = "Parchment"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "8d49c66a-9870-4c97-9499-684f84a8e432"
            }
        }
    }
}

// Faster desktop start: the bundled runtime gets a base class-data-sharing archive, and the packaged app keeps
// its own archive of the classes it loads beside its files (rebuilt automatically after an update).
// Packaged app only; the dev run is unaffected. Measured 2026-11-11: about 3.1 s to 2.0 s with 300 chats.
tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask>().configureEach {
    freeArgs.add("--generate-cds-archive")
    // jlink builds the archive with the image's own java.exe, which --strip-native-commands removes first.
    // Compose has no public switch, so turn stripping off here (this fails loudly if Compose renames it)
    // and delete the launchers afterwards: the app starts through Parchment.exe and jvm.dll.
    @Suppress("UNCHECKED_CAST")
    (javaClass.getMethod("getStripNativeCommands\$compose").invoke(this) as Property<Boolean>).set(false)
    val runtimeBin = destinationDir.dir("bin")
    doLast {
        runtimeBin.get().asFile.listFiles { file -> file.extension.equals("exe", ignoreCase = true) }?.forEach { it.delete() }
    }
}
tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>().configureEach {
    freeArgs.addAll("--java-options", "-XX:+AutoCreateSharedArchive", "--java-options", "-XX:SharedArchiveFile=\$APPDIR/parchment.jsa")
}
