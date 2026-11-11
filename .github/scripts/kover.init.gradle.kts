// Used only by the quality-signals workflow (gradle --init-script): adds Kover so CI can measure test coverage,
// without local builds downloading or configuring it.
initscript {
    repositories { mavenCentral(); gradlePluginPortal() }
    dependencies { classpath("org.jetbrains.kotlinx:kover-gradle-plugin:0.9.11") }
}

// By class: a plugin on the init script's classpath can't be found by its id from inside a project.
allprojects {
    if (name == "composeApp") apply<kotlinx.kover.gradle.plugin.KoverGradlePlugin>()
}
