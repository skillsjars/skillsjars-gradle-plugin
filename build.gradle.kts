import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    `kotlin-dsl`

    embeddedKotlin("jvm")
    embeddedKotlin("plugin.power-assert")
    embeddedKotlin("plugin.serialization")

    id("com.vanniktech.maven.publish") version "0.36.0"
}

group = "com.skillsjars"
version = "0.0.1"

gradlePlugin {
    website = "https://github.com/skillsjars/skillsjars-gradle-plugin"
    vcsUrl = "https://github.com/skillsjars/skillsjars-gradle-plugin.git"
    plugins {
        create("SkillsJarsGradlePlugin") {
            id = "com.skillsjars.gradle-plugin"
            implementationClass = "com.skillsjars.gradleplugin.SkillsJarsGradlePlugin"
            displayName = "SkillsJars Gradle Plugin"
            description = "Gradle tasks for SkillsJars"
            tags = listOf("ai")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(embeddedKotlin("test"))
    testImplementation(gradleTestKit())
}

tasks.withType<Test> {
    useJUnitPlatform()

    testLogging {
        showStandardStreams = true
        exceptionFormat = TestExceptionFormat.FULL
        events(TestLogEvent.STARTED, TestLogEvent.PASSED, TestLogEvent.SKIPPED, TestLogEvent.FAILED)
    }
}

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    pom {
        name = "SkillsJars Gradle Plugin"
        description = "Gradle Plugin for SkillsJars"
        inceptionYear = "2026"
        url = "https://github.com/skillsjars/skillsjars-gradle-plugin"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }
        developers {
            developer {
                id = "jamesward"
                name = "James Ward"
                email = "james@jamesward.com"
                url = "https://jamesward.com"
            }
        }
        scm {
            url = "https://github.com/skillsjars/skillsjars-gradle-plugin"
            connection = "https://github.com/skillsjars/skillsjars-gradle-plugin.git"
            developerConnection = "scm:git:git@github.com:skillsjars/skillsjars-gradle-plugin.git"
        }
    }
}

