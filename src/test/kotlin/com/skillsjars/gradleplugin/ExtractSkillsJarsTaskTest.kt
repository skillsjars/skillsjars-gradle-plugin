package com.skillsjars.gradleplugin

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.io.FileOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExtractSkillsJarsTaskTest {

    private lateinit var projectDir: File

    @BeforeTest
    fun setUp() {
        projectDir = createTempDirectory("skillsjars-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        projectDir.deleteRecursively()
    }

    @Test
    fun `extract without dir parameter fails`() {
        writeSettingsFile()
        writeBuildFile()

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("extractSkillsJars")
            .withPluginClasspath()
            .buildAndFail()

        assertTrue(result.output.contains("dir"))
    }

    @Test
    fun `extract skillsjars`() {
        setupLocalRepo("test-skill")
        writeSettingsFile()
        writeBuildFile(
            dependencies = """implementation("com.skillsjars:test-skill:1.0.0")"""
        )

        val outputDir = File(projectDir, "output")

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("extractSkillsJars", "-Pdir=${outputDir.absolutePath}")
            .withPluginClasspath()
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":extractSkillsJars")?.outcome)

        val skillMd = File(outputDir, "skillsjars__org__repo__skill/SKILL.md")
        assertTrue(skillMd.exists(), "SKILL.md should exist")

        val testFile = File(outputDir, "skillsjars__org__repo__skill/test.txt")
        assertTrue(testFile.exists(), "test.txt should exist")
        assertEquals("test content", testFile.readText())

        val nestedFile = File(outputDir, "skillsjars__org__repo__skill/foo/nested.txt")
        assertTrue(nestedFile.exists(), "Nested file should exist")
        assertEquals("nested content", nestedFile.readText())
    }

    @Test
    fun `conflicting paths throws error`() {
        setupLocalRepo("skill1")
        setupLocalRepo("skill2")
        writeSettingsFile()
        writeBuildFile(
            dependencies = """
                implementation("com.skillsjars:skill1:1.0.0")
                implementation("com.skillsjars:skill2:1.0.0")
            """.trimIndent()
        )

        val outputDir = File(projectDir, "output")

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("extractSkillsJars", "-Pdir=${outputDir.absolutePath}")
            .withPluginClasspath()
            .buildAndFail()

        assertTrue(result.output.contains("conflict"), "Should report path conflict")
    }

    @Test
    fun `non-skillsjars dependencies are ignored`() {
        setupLocalRepo("test-skill")
        setupLocalRepo("other-lib", group = "com.example")
        writeSettingsFile()
        writeBuildFile(
            dependencies = """
                implementation("com.skillsjars:test-skill:1.0.0")
                implementation("com.example:other-lib:1.0.0")
            """.trimIndent()
        )

        val outputDir = File(projectDir, "output")

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("extractSkillsJars", "-Pdir=${outputDir.absolutePath}")
            .withPluginClasspath()
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":extractSkillsJars")?.outcome)
        assertTrue(result.output.contains("Found 1 SkillsJar(s)"))
    }

    private fun writeSettingsFile() {
        File(projectDir, "settings.gradle.kts").writeText("")
    }

    private fun writeBuildFile(dependencies: String = "") {
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                java
                id("com.skillsjars.gradle-plugin")
            }

            repositories {
                maven { url = uri("repo") }
            }

            dependencies {
                $dependencies
            }
            """.trimIndent()
        )
    }

    private fun setupLocalRepo(artifactId: String, group: String = "com.skillsjars") {
        val groupPath = group.replace(".", "/")
        val artifactDir = File(projectDir, "repo/$groupPath/$artifactId/1.0.0")
        artifactDir.mkdirs()

        createTestSkillsJar(File(artifactDir, "$artifactId-1.0.0.jar"))

        File(artifactDir, "$artifactId-1.0.0.pom").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>$group</groupId>
                <artifactId>$artifactId</artifactId>
                <version>1.0.0</version>
            </project>
            """.trimIndent()
        )
    }

    private fun createTestSkillsJar(file: File) {
        JarOutputStream(FileOutputStream(file)).use { jos ->
            // Add SKILL.md marker
            jos.putNextEntry(JarEntry("META-INF/resources/skills/org/repo/skill/SKILL.md"))
            jos.write("# Test Skill".toByteArray())
            jos.closeEntry()

            // Add file at root of skill
            jos.putNextEntry(JarEntry("META-INF/resources/skills/org/repo/skill/test.txt"))
            jos.write("test content".toByteArray())
            jos.closeEntry()

            // Add nested file
            jos.putNextEntry(JarEntry("META-INF/resources/skills/org/repo/skill/foo/nested.txt"))
            jos.write("nested content".toByteArray())
            jos.closeEntry()
        }
    }
}
