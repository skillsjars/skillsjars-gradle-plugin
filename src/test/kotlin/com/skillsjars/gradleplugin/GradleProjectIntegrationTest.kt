package com.skillsjars.gradleplugin

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.util.jar.JarFile
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration test modeled after a multi-project build that verifies the packaging of local skills
 * in a producer subproject (`:skills`) and the extraction of skills
 * in a consumer subproject (`:app`) using Gradle TestKit.
 */
class GradleProjectIntegrationTest {

    private lateinit var projectDir: File

    @BeforeTest
    fun setUp() {
        projectDir = createTempDirectory("skillsjars-gradle-project-test").toFile()
        setupProjectStructure()
    }

    @AfterTest
    fun tearDown() {
        projectDir.deleteRecursively()
    }

    @Test
    fun `package and extract skillsjars in multi-project build`() {
        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments(":skills:jar", ":app:extractSkillsJars")
            .withPluginClasspath()
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":skills:packageSkillsJars")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":skills:jar")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:extractSkillsJars")?.outcome)

        // 1. Verify packaged output in :skills build directory
        val generatedSkill = File(
            projectDir,
            "skills/build/generated/resources/skillsjars/META-INF/skills/com/example/hello-world/SKILL.md"
        )
        assertTrue(generatedSkill.exists(), "Packaged SKILL.md should exist in :skills generated resources")
        assertTrue(generatedSkill.readText().contains("name: hello-world"))

        // 2. Verify extracted outputs in .agents/skills directory
        val agentsSkillsDir = File(projectDir, ".agents/skills")
        assertTrue(agentsSkillsDir.exists(), "Target .agents/skills directory should exist")

        val extractedLocalSkill = File(agentsSkillsDir, "skillsjars__com__example__hello-world/SKILL.md")
        assertTrue(extractedLocalSkill.exists(), "Extracted local skill SKILL.md should exist")
        assertTrue(
            extractedLocalSkill.readText().contains("Instructions and guidelines for the Hello World application"),
            "Extracted local skill should have correct content"
        )
    }

    @Test
    fun `skills subproject jar includes packaged skills`() {
        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments(":skills:jar")
            .withPluginClasspath()
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":skills:jar")?.outcome)

        val jarFile = File(projectDir, "skills/build/libs/skills-1.0.0-SNAPSHOT.jar")
        assertTrue(jarFile.exists(), "skills JAR should exist")

        JarFile(jarFile).use { jar ->
            val entry = jar.getJarEntry("META-INF/skills/com/example/hello-world/SKILL.md")
            assertTrue(entry != null, "JAR should contain META-INF/skills/com/example/hello-world/SKILL.md")
        }
    }

    @Test
    fun `clean task deletes extracted skills directory`() {
        // First build jar and extract skills
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments(":skills:jar", ":app:extractSkillsJars")
            .withPluginClasspath()
            .build()

        val agentsSkillsDir = File(projectDir, ".agents/skills")
        assertTrue(agentsSkillsDir.exists(), "Target .agents/skills directory should exist after extraction")

        // Then execute clean
        val cleanResult = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments(":app:clean")
            .withPluginClasspath()
            .build()

        assertEquals(TaskOutcome.SUCCESS, cleanResult.task(":app:clean")?.outcome)
        assertFalse(agentsSkillsDir.exists(), "Clean task should delete configured outputDir (.agents/skills)")
    }

    private fun setupProjectStructure() {
        // Root settings.gradle
        File(projectDir, "settings.gradle").writeText(
            """
            rootProject.name = 'normal-git-project'
            include 'app', 'skills'
            """.trimIndent()
        )

        // Root build.gradle
        File(projectDir, "build.gradle").writeText(
            """
            plugins {
                id 'com.skillsjars.gradle-plugin' apply false
            }
            """.trimIndent()
        )

        // :skills subproject
        val skillsDir = File(projectDir, "skills")
        skillsDir.mkdirs()
        File(skillsDir, "build.gradle").writeText(
            """
            plugins {
                id 'java'
                id 'com.skillsjars.gradle-plugin'
            }

            group = 'com.example'
            version = '1.0.0-SNAPSHOT'

            skillsjars {
                sourceDir = file('skills')
            }
            """.trimIndent()
        )

        val helloWorldSkillDir = File(skillsDir, "skills/hello-world")
        helloWorldSkillDir.mkdirs()
        File(helloWorldSkillDir, "SKILL.md").writeText(
            """
            ---
            name: hello-world
            description: Instructions and guidelines for the Hello World application
            ---

            # Hello World Skill

            This skill provides context and instructions for maintaining and running the Hello World application.

            ## Overview
            - Main class: `com.example.App`
            - Gradle module: `:app`
            - Test framework: JUnit Jupiter 5
            """.trimIndent()
        )

        // :app subproject
        val appDir = File(projectDir, "app")
        appDir.mkdirs()
        File(appDir, "build.gradle").writeText(
            """
            plugins {
                id 'application'
                id 'com.skillsjars.gradle-plugin'
            }

            group = 'com.example'
            version = '1.0.0-SNAPSHOT'

            application {
                mainClass = 'com.example.App'
            }

            skillsjars {
                outputDir = file("${'$'}{rootDir}/.agents/skills")
            }

            dependencies {
                skill project(':skills')
            }
            """.trimIndent()
        )

        val appSrcMain = File(appDir, "src/main/java/com/example")
        appSrcMain.mkdirs()
        File(appSrcMain, "App.java").writeText(
            """
            package com.example;

            public class App {
                public String getGreeting() {
                    return "Hello World!";
                }

                public static void main(String[] args) {
                    System.out.println(new App().getGreeting());
                }
            }
            """.trimIndent()
        )
    }
}
