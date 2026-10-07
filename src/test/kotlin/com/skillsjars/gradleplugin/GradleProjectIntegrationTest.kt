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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration test modeled after a multi-project build that verifies the packaging of local skills
 * in a producer subproject (`:skills`) and the extraction of skills
 * in a consumer subproject (`:app`) using Gradle TestKit, also when a configuration cache entry is reused.
 */
class GradleProjectIntegrationTest {

    private lateinit var projectDir: File

    @BeforeTest
    fun setUp() {
        projectDir = createTempDirectory("skillsjars-gradle-project-test").toFile()
        enableConfigurationCache(projectDir)
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

    @Test
    fun `extract reuses the configuration cache entry and reflects changed project skills`() {
        val runner = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments(":app:extractSkillsJars")
            .withPluginClasspath()

        val first = runner.build()

        assertTrue(first.output.contains(CONFIGURATION_CACHE_STORED), "First run should store a configuration cache entry")
        assertEquals(TaskOutcome.SUCCESS, first.task(":skills:jar")?.outcome, "Extraction should build the skills jar it extracts")

        val agentsSkillsDir = File(projectDir, ".agents/skills")
        val helloWorldSkill = File(agentsSkillsDir, "skillsjars__com__example__hello-world/SKILL.md")
        val goodbyeWorldSkill = File(agentsSkillsDir, "skillsjars__com__example__goodbye-world/SKILL.md")
        assertTrue(helloWorldSkill.exists(), "Extracted local skill SKILL.md should exist")

        File(projectDir, "skills/skills/hello-world/SKILL.md").writeText(
            """
            ---
            name: hello-world
            description: Updated instructions for the Hello World application
            ---
            """.trimIndent()
        )
        val goodbyeWorldSkillDir = File(projectDir, "skills/skills/goodbye-world")
        goodbyeWorldSkillDir.mkdirs()
        File(goodbyeWorldSkillDir, "SKILL.md").writeText("# Goodbye World Skill")

        val changed = runner.build()

        assertTrue(changed.output.contains(CONFIGURATION_CACHE_REUSED), "Second run should reuse the configuration cache entry")
        assertEquals(TaskOutcome.SUCCESS, changed.task(":app:extractSkillsJars")?.outcome)
        assertTrue(
            helloWorldSkill.readText().contains("Updated instructions for the Hello World application"),
            "Changed skill should be extracted"
        )
        assertTrue(goodbyeWorldSkill.exists(), "Added skill should be extracted")

        File(projectDir, "skills/skills/hello-world").deleteRecursively()

        val removed = runner.build()

        assertTrue(removed.output.contains(CONFIGURATION_CACHE_REUSED), "Third run should reuse the configuration cache entry")
        assertFalse(helloWorldSkill.parentFile.exists(), "Removed skill should no longer be extracted")
        assertTrue(goodbyeWorldSkill.exists(), "Remaining skill should still be extracted")
    }

    @Test
    fun `extracting a project dependency does not build the projects only on its runtime classpath`() {
        File(projectDir, "settings.gradle").appendText("\ninclude 'lib'\n")
        File(projectDir, "skills/build.gradle").appendText(
            """

            dependencies {
                runtimeOnly project(':lib')
            }
            """.trimIndent()
        )
        val libSources = File(projectDir, "lib/src/main/java/com/example")
        libSources.mkdirs()
        File(projectDir, "lib/build.gradle").writeText("plugins { id 'java' }")
        File(libSources, "Broken.java").writeText("package com.example; class Broken {")

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments(":app:extractSkillsJars")
            .withPluginClasspath()
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":app:extractSkillsJars")?.outcome)
        assertNull(result.task(":lib:compileJava"), ":lib should not be built to extract the skills of :skills")
        assertNull(result.task(":lib:jar"), ":lib should not be built to extract the skills of :skills")
        assertTrue(File(projectDir, ".agents/skills/skillsjars__com__example__hello-world/SKILL.md").exists())
    }

    @Test
    fun `transitive project skills are logged and require a direct skill dependency`() {
        File(projectDir, "settings.gradle").appendText("\ninclude 'extra-skills'\n")
        File(projectDir, "skills/build.gradle").appendText(
            """

            dependencies {
                implementation project(':extra-skills')
            }
            """.trimIndent()
        )
        val skillsSource = File(projectDir, "skills/src/main/java/com/example/SkillHelper.java")
        skillsSource.parentFile.mkdirs()
        skillsSource.writeText("package com.example; public class SkillHelper {}")
        val extraSkillsDir = File(projectDir, "extra-skills")
        extraSkillsDir.mkdirs()
        File(extraSkillsDir, "build.gradle").writeText(
            """
            plugins {
                id 'java'
                id 'com.skillsjars.gradle-plugin'
            }
            group = 'com.example'
            version = '1.0.0-SNAPSHOT'
            """.trimIndent()
        )
        val extraSkill = File(extraSkillsDir, "skills/extra/SKILL.md")
        extraSkill.parentFile.mkdirs()
        extraSkill.writeText("# Extra skill")

        val runner = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments(":app:extractSkillsJars", "--info")
            .withPluginClasspath()
        val extractedExtra = File(projectDir, ".agents/skills/skillsjars__com__example__extra/SKILL.md")
        val skipMessage = "Skipping project ':extra-skills': transitive project dependencies are not extracted"

        val first = runner.build()

        assertTrue(first.output.contains(CONFIGURATION_CACHE_STORED))
        assertEquals(TaskOutcome.SUCCESS, first.task(":extra-skills:jar")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, first.task(":app:extractSkillsJars")?.outcome)
        assertTrue(first.output.contains(skipMessage), "The excluded project should be named at info level")
        assertFalse(extractedExtra.exists(), "A transitive project dependency should not be extracted")
        assertTrue(File(projectDir, ".agents/skills/skillsjars__com__example__hello-world/SKILL.md").exists())

        val reused = runner.build()

        assertTrue(reused.output.contains(CONFIGURATION_CACHE_REUSED))
        assertEquals(TaskOutcome.SUCCESS, reused.task(":app:extractSkillsJars")?.outcome)
        assertTrue(reused.output.contains(skipMessage), "The excluded project should be logged on cache reuse too")
        assertFalse(extractedExtra.exists())

        File(projectDir, "app/build.gradle").appendText("\ndependencies { skill project(':extra-skills') }\n")

        val declared = runner.build()

        assertEquals(TaskOutcome.SUCCESS, declared.task(":app:extractSkillsJars")?.outcome)
        assertFalse(declared.output.contains(skipMessage), "A direct skill dependency should not be logged as skipped")
        assertTrue(declared.output.contains("Found 2 SkillsJar(s)"))
        assertEquals("# Extra skill", extractedExtra.readText())
    }

    @Test
    fun `package and extract skillsjars without the configuration cache`() {
        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments(":app:extractSkillsJars", "--no-configuration-cache")
            .withPluginClasspath()
            .build()

        assertFalse(result.output.contains("Configuration cache entry"), "The build should run without the configuration cache")
        assertEquals(TaskOutcome.SUCCESS, result.task(":skills:jar")?.outcome, "Extraction should build the skills jar it extracts")
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:extractSkillsJars")?.outcome)
        assertTrue(File(projectDir, ".agents/skills/skillsjars__com__example__hello-world/SKILL.md").exists())
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
