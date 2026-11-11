// Used only by the release workflow (gradle --init-script): adds the CycloneDX plugin so CI can publish an SBOM,
// a list of every library in the app, without local builds downloading or configuring it.
initscript {
    repositories { mavenCentral(); gradlePluginPortal() }
    dependencies { classpath("org.cyclonedx:cyclonedx-gradle-plugin:3.5.1") }
}

// By class: a plugin on the init script's classpath can't be found by its id from inside a project.
allprojects {
    apply<org.cyclonedx.gradle.CyclonedxPlugin>()
}
