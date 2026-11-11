// Used only by the quality-signals workflow (gradle --init-script): adds Kover so CI can measure test coverage,
// without local builds downloading or configuring it.
initscript {
    repositories { gradlePluginPortal() }
    dependencies { classpath("org.jetbrains.kotlinx:kover-gradle-plugin:0.9.11") }
}

allprojects {
    if (name == "composeApp") apply(plugin = "org.jetbrains.kotlinx.kover")
}
