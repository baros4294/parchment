// Used only by CI (gradle --init-script): adds the CycloneDX plugin so CI can publish an SBOM, a list of every
// library in the app, without local builds downloading or configuring it.
initscript {
    repositories { mavenCentral(); gradlePluginPortal() }
    dependencies { classpath("org.cyclonedx:cyclonedx-gradle-plugin:3.5.1") }
}

// Applied to the root only: that registers one Direct SBOM task per project and the Aggregate SBOM
// (build/reports/cyclonedx/bom.json) on the root. By class: a plugin on the init script's classpath can't be
// found by its id from inside a project.
rootProject {
    apply<org.cyclonedx.gradle.CyclonedxPlugin>()
}

// List what ships, the same classpaths dependency-submission.yml reports: the desktop app's runtime and the
// Android release APK's runtime. Scanning every configuration pulls in build tooling and test libraries.
allprojects {
    tasks.withType<org.cyclonedx.gradle.CyclonedxDirectTask>().configureEach {
        includeConfigs.set(listOf("desktopRuntimeClasspath", "releaseRuntimeClasspath"))
    }
}
