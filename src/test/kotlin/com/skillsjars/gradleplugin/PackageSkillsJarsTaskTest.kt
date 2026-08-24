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
 * Tests for [PackageSkillsJarsTask] verifying packaging with GitHub coordinates, group path fallback,
 * `allowed-tools` frontmatter validation, directory filtering, and integration with `jar` creation.
 */
class PackageSkillsJarsTaskTest {

    private lateinit var projectDir: File

    @BeforeTest
    fun setUp() {
        projectDir = createTempDirectory("skillsjars-package-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        projectDir.deleteRecursively()
    }

    @Test
    fun `package skills with github coordinates`() {
        writeSettingsFile()
        writeBuildFile(
            extensionConfig = """
                skillsjars {
                    gitHubUrl.set("https://github.com/testorg/testrepo")
                    allowedTools.put("test-skill", "Bash Read Edit")
                }
            """.trimIndent()
        )

        createLocalSkill(
            skillDirName = "test-skill",
            skillMdContent = """
                ---
                name: test-skill
                description: A test skill
                allowed-tools: Bash Read Edit
                ---
                # Test Skill
            """.trimIndent(),
            extraFiles = mapOf("nested/prompt.txt" to "hello github")
        )

        createLocalSkill(
            skillDirName = "second-skill",
            skillMdContent = "# Second Skill",
            extraFiles = mapOf("docs/usage.md" to "second docs")
        )

        val ignoredDir = File(projectDir, "skills/ignored-dir")
        ignoredDir.mkdirs()
        File(ignoredDir, "data.txt").writeText("ignored")

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("packageSkillsJars")
            .withPluginClasspath()
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":packageSkillsJars")?.outcome)

        val outputDir = File(projectDir, "build/generated/resources/skillsjars")
        val skill1Md = File(outputDir, "META-INF/skills/testorg/testrepo/test-skill/SKILL.md")
        assertTrue(skill1Md.exists(), "test-skill SKILL.md should exist")

        val promptFile = File(outputDir, "META-INF/skills/testorg/testrepo/test-skill/nested/prompt.txt")
        assertTrue(promptFile.exists(), "nested prompt.txt should exist")
        assertEquals("hello github", promptFile.readText().trim())

        val skill2Md = File(outputDir, "META-INF/skills/testorg/testrepo/second-skill/SKILL.md")
        assertTrue(skill2Md.exists(), "second-skill SKILL.md should exist")

        val ignoredOutputFile = File(outputDir, "META-INF/skills/testorg/testrepo/ignored-dir/data.txt")
        assertFalse(ignoredOutputFile.exists(), "Directories without SKILL.md should not be packaged")
    }

    @Test
    fun `package skills with group fallback`() {
        writeSettingsFile()
        writeBuildFile(
            group = "com.example.test",
            extensionConfig = ""
        )

        createLocalSkill(
            skillDirName = "my-skill",
            skillMdContent = "# My Skill",
            extraFiles = mapOf("data.txt" to "sample data")
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("packageSkillsJars")
            .withPluginClasspath()
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":packageSkillsJars")?.outcome)

        val outputDir = File(projectDir, "build/generated/resources/skillsjars")
        val skillMd = File(outputDir, "META-INF/skills/com/example/test/my-skill/SKILL.md")
        assertTrue(skillMd.exists(), "SKILL.md should be placed under group path")

        val dataFile = File(outputDir, "META-INF/skills/com/example/test/my-skill/data.txt")
        assertTrue(dataFile.exists(), "data.txt should be placed under group path")
        assertEquals("sample data", dataFile.readText())
    }

    @Test
    fun `package skills fails when allowed-tools is missing in build configuration`() {
        writeSettingsFile()
        writeBuildFile(
            extensionConfig = """
                skillsjars {
                    gitHubUrl.set("https://github.com/testorg/testrepo")
                }
            """.trimIndent()
        )

        createLocalSkill(
            skillDirName = "test-skill",
            skillMdContent = """
                ---
                name: test-skill
                description: A test skill
                allowed-tools: Bash Read Edit
                ---
                # Test Skill
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("packageSkillsJars")
            .withPluginClasspath()
            .buildAndFail()

        assertTrue(result.output.contains("missing 'skillsjars.skill.test-skill.allowed-tools'"))
    }

    @Test
    fun `package skills fails when allowed-tools value mismatches`() {
        writeSettingsFile()
        writeBuildFile(
            extensionConfig = """
                skillsjars {
                    gitHubUrl.set("https://github.com/testorg/testrepo")
                    allowedTools.put("test-skill", "Read Edit")
                }
            """.trimIndent()
        )

        createLocalSkill(
            skillDirName = "test-skill",
            skillMdContent = """
                ---
                name: test-skill
                description: A test skill
                allowed-tools: Bash Read Edit
                ---
                # Test Skill
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("packageSkillsJars")
            .withPluginClasspath()
            .buildAndFail()

        assertTrue(result.output.contains("does not match SKILL.md allowed-tools"))
    }

    @Test
    fun `packaged jar includes compiled classes and skills`() {
        writeSettingsFile()
        writeBuildFile(
            group = "com.example.test",
            extensionConfig = """
                skillsjars {
                    gitHubUrl.set("https://github.com/testorg/testrepo")
                }
            """.trimIndent()
        )

        // Add a Java class
        val srcJava = File(projectDir, "src/main/java/example")
        srcJava.mkdirs()
        File(srcJava, "Greeter.java").writeText(
            """
            package example;
            public class Greeter {
                public String greet() { return "Hello"; }
            }
            """.trimIndent()
        )

        // Add a skill
        createLocalSkill(
            skillDirName = "helper-skill",
            skillMdContent = "# Helper Skill",
            extraFiles = mapOf("prompts/system.txt" to "system prompt")
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("jar")
            .withPluginClasspath()
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":jar")?.outcome)

        val jarFile = File(projectDir, "build/libs").listFiles { f -> f.name.endsWith(".jar") }?.firstOrNull()
        assertTrue(jarFile != null && jarFile.exists(), "JAR file should be created")

        val entryNames = mutableSetOf<String>()
        JarFile(jarFile).use { jar ->
            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                entryNames.add(entries.nextElement().name)
            }
        }

        assertTrue(entryNames.contains("example/Greeter.class"), "Jar should contain Greeter.class")
        assertTrue(entryNames.contains("META-INF/skills/testorg/testrepo/helper-skill/SKILL.md"), "Jar should contain SKILL.md")
        assertTrue(entryNames.contains("META-INF/skills/testorg/testrepo/helper-skill/prompts/system.txt"), "Jar should contain prompt file")
    }

    private fun writeSettingsFile() {
        File(projectDir, "settings.gradle.kts").writeText("rootProject.name = \"test-package-project\"")
    }

    private fun writeBuildFile(group: String = "com.example", extensionConfig: String = "") {
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                java
                id("com.skillsjars.gradle-plugin")
            }

            group = "$group"
            version = "1.0.0"

            $extensionConfig
            """.trimIndent()
        )
    }

    private fun createLocalSkill(skillDirName: String, skillMdContent: String, extraFiles: Map<String, String> = emptyMap()) {
        val skillDir = File(projectDir, "skills/$skillDirName")
        skillDir.mkdirs()
        File(skillDir, "SKILL.md").writeText(skillMdContent)

        for ((relPath, content) in extraFiles) {
            val file = File(skillDir, relPath)
            file.parentFile.mkdirs()
            file.writeText(content)
        }
    }
}
