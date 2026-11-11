import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The shared module: all app code for desktop and Android. The Android APK itself (applicationId, signing,
// R8) is built by :androidApp, because AGP 9 no longer lets an application live in a multiplatform module.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

val appVersionProp = (project.findProperty("appVersion") as? String)?.removePrefix("v")?.trim()
    ?.takeIf { it.isNotEmpty() } ?: "2.1.0"
val appVersionCodeProp = (project.findProperty("appVersionCode") as? String)?.toIntOrNull() ?: 200
val githubRepoProp = (project.findProperty("githubRepo") as? String)?.trim()
    ?.takeIf { it.isNotEmpty() } ?: "baros4294/parchment"
// Set only by .github/workflows/build-installers.yml. Dev runs and local packages must never install an update over the real app.
val officialReleaseProp = (project.findProperty("officialRelease") as? String)?.trim().toBoolean()

val generateBuildConfig = tasks.register("generateBuildConfig") {
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

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "io.parchment.ai"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        // res/ holds the launcher icon, strings and the accessibility service config.
        androidResources { enable = true }
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
        val desktopMain = getByName("desktopMain")
        val androidMain = getByName("androidMain")
        desktopMain.kotlin.srcDir("src/jvmShared/kotlin")

        commonMain {
            kotlin.srcDir(generateBuildConfig.map { it.outputs.files.singleFile })
            dependencies {
            // Compose Foundation and UI only: no Material.
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)

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
            "-XX:SoftRefLRUPolicyMSPerMB=250",
            // JNA (title bar, memory check) and Skia call native code; JDK 25 warns and later JDKs will block it.
            "--enable-native-access=ALL-UNNAMED"
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
        val bin = runtimeBin.get().asFile
        bin.listFiles { file -> file.extension.equals("exe", ignoreCase = true) }?.forEach { it.delete() }
        // The JVM reads this second archive only when compressed object pointers are off (heaps over about 32 GB);
        // Parchment's heap is capped at 1 GB, so it would ship 12 MB that is never used.
        bin.resolve("server/classes_nocoops.jsa").delete()
    }
}
tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>().configureEach {
    freeArgs.addAll("--java-options", "-XX:+AutoCreateSharedArchive", "--java-options", "-XX:SharedArchiveFile=\$APPDIR/parchment.jsa")
}
