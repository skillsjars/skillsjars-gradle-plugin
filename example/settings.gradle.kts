plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Use local plugin for testing
val parentSettingsFile = file("../settings.gradle.kts")
if (parentSettingsFile.exists()) {
    includeBuild("..")
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }
}

rootProject.name = "skillsjars-gradle-plugin-example"
