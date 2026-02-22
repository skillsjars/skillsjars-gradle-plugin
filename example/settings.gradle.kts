//pluginManagement {
//    repositories {
//        mavenCentral()
//        gradlePluginPortal()
//        mavenLocal()
//    }
//}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }
    pluginManagement {
        repositories {
            mavenCentral()
            mavenLocal()
        }
    }
}

rootProject.name = "skillsjars-gradle-plugin-example"
