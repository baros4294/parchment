import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey

// The Android APK shell. All code, the manifest entries and res/ live in :composeApp (androidMain) and merge in;
// this module owns only what an application needs: its id, version, signing, R8 and the bundled fonts.
plugins {
    alias(libs.plugins.androidApplication)
}

val appVersionProp = (project.findProperty("appVersion") as? String)?.removePrefix("v")?.trim()
    ?.takeIf { it.isNotEmpty() } ?: "2.1.0"
val appVersionCodeProp = (project.findProperty("appVersionCode") as? String)?.toIntOrNull() ?: 200

// Checks the private signer before a release build. It reads everything when it runs, so it holds no
// reference to the build script and works with the configuration cache.
val validatePrivateReleaseSigner = tasks.register("validatePrivateReleaseSigner") {
    doLast {
        val location = System.getenv("ANDROID_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
            ?: error("Release signing requires ANDROID_KEYSTORE_PATH; load the private signer first.")
        val password = System.getenv("ANDROID_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
            ?: error("Release signing requires ANDROID_KEYSTORE_PASSWORD.")
        val alias = System.getenv("ANDROID_KEY_ALIAS")?.takeIf { it.isNotBlank() }
            ?: error("Release signing requires ANDROID_KEY_ALIAS.")
        val store = KeyStore.getInstance(File(location), password.toCharArray())
        require(store.isKeyEntry(alias)) { "Release signer alias must contain a private key." }
        val certificate = store.getCertificate(alias) ?: error("Release signer certificate is unavailable.")
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        require(fingerprint != "ed96a0a733e9e6d38633f04a6a182c997fe60d480d9e33e5f078faa2536d26eb") {
            "The exposed repository key cannot sign a release. Use the private signer and migration plan."
        }
        // A blank key password falls back to the store password.
        val keyPassword = (System.getenv("ANDROID_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: password).toCharArray()
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

android {
    // Differs from the shared module's namespace (io.parchment.ai); the applicationId stays the same,
    // so installed apps keep updating.
    namespace = "io.parchment.ai.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

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
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
                    ?: System.getenv("ANDROID_KEYSTORE_PASSWORD")
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
            // composeApp/src/commonMain/resources also reaches the APK as Java resources. Android reads the fonts
            // from assets (added below) and never the desktop icon, so these copies only doubled the size.
            excludes += "/fonts/**"
            excludes += "/icon/**"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Android loads the bundled fonts through AssetManager ("fonts/Inter-Regular.ttf"); desktop reads the same
// folder as class-path resources.
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addStaticSourceDirectory(rootProject.file("composeApp/src/commonMain/resources").path)
    }
}

dependencies {
    implementation(project(":composeApp"))
}
