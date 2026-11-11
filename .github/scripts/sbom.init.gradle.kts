// Used only by the release workflow (gradle --init-script): adds the CycloneDX plugin so CI can publish an SBOM,
// a list of every library in the app, without local builds downloading or configuring it.
initscript {
    repositories { gradlePluginPortal() }
    dependencies { classpath("org.cyclonedx:cyclonedx-gradle-plugin:3.5.1") }
}

allprojects {
    apply(plugin = "org.cyclonedx.bom")
}
