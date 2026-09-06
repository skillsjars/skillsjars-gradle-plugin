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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [ExtractSkillsJarsTask] verifying extraction from `skill` configuration,
 * output directory precedence, group-agnostic extraction, collision detection, and clean task behavior.
 */
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
    fun `extract without output directory fails`() {
        writeSettingsFile()
        writeBuildFile()

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("extractSkillsJars")
            .withPluginClasspath()
            .buildAndFail()

        assertTrue(result.output.contains("output directory is required"))
    }

    @Test
    fun `extract skillsjars using skill configuration`() {
        setupLocalRepo("test-skill", group = "org.custom")
        writeSettingsFile()
        writeBuildFile(
            dependencies = """skill("org.custom:test-skill:1.0.0")"""
        )

        val outputDir = File(projectDir, "output")

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("extractSkillsJars", "-PoutputDir=${outputDir.absolutePath}")
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
    fun `extract skillsjars with extension outputDir`() {
        setupLocalRepo("test-skill", group = "com.other")
        writeSettingsFile()
        writeBuildFile(
            extensionConfig = """
                skillsjars {
                    outputDir.set(layout.projectDirectory.dir("configured-output"))
                }
            """.trimIndent(),
            dependencies = """skill("com.other:test-skill:1.0.0")"""
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("extractSkillsJars")
            .withPluginClasspath()
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":extractSkillsJars")?.outcome)

        val outputDir = File(projectDir, "configured-output")
        val skillMd = File(outputDir, "skillsjars__org__repo__skill/SKILL.md")
        assertTrue(skillMd.exists(), "SKILL.md should exist in configured output dir")
    }

    @Test
    fun `cli -P option takes precedence over extension outputDir`() {
        setupLocalRepo("test-skill")
        writeSettingsFile()
        writeBuildFile(
            extensionConfig = """
                skillsjars {
                    outputDir.set(layout.projectDirectory.dir("extension-output"))
                }
            """.trimIndent(),
            dependencies = """skill("com.skillsjars:test-skill:1.0.0")"""
        )

        val cliOutputDir = File(projectDir, "cli-output")

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("extractSkillsJars", "-PoutputDir=${cliOutputDir.absolutePath}")
            .withPluginClasspath()
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":extractSkillsJars")?.outcome)

        assertTrue(File(cliOutputDir, "skillsjars__org__repo__skill/SKILL.md").exists(), "CLI output dir should be used")
        assertFalse(File(projectDir, "extension-output").exists(), "Extension output dir should NOT be used")
    }

    @Test
    fun `cli -Pdir backward compatibility`() {
        setupLocalRepo("test-skill")
        writeSettingsFile()
        writeBuildFile(
            dependencies = """skill("com.skillsjars:test-skill:1.0.0")"""
        )

        val outputDir = File(projectDir, "output-legacy")

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("extractSkillsJars", "-Pdir=${outputDir.absolutePath}")
            .withPluginClasspath()
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":extractSkillsJars")?.outcome)
        assertTrue(File(outputDir, "skillsjars__org__repo__skill/SKILL.md").exists())
    }

    @Test
    fun `conflicting paths throws error`() {
        setupLocalRepo("skill1")
        setupLocalRepo("skill2")
        writeSettingsFile()
        writeBuildFile(
            dependencies = """
                skill("com.skillsjars:skill1:1.0.0")
                skill("com.skillsjars:skill2:1.0.0")
            """.trimIndent()
        )

        val outputDir = File(projectDir, "output")

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("extractSkillsJars", "-PoutputDir=${outputDir.absolutePath}")
            .withPluginClasspath()
            .buildAndFail()

        assertTrue(result.output.contains("conflict"), "Should report path conflict")
    }

    @Test
    fun `extract skillsjars with META-INF skills prefix`() {
        setupLocalRepo("test-skill", skillsPrefix = "META-INF/skills/")
        writeSettingsFile()
        writeBuildFile(
            dependencies = """skill("com.skillsjars:test-skill:1.0.0")"""
        )

        val outputDir = File(projectDir, "output")

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("extractSkillsJars", "-PoutputDir=${outputDir.absolutePath}")
            .withPluginClasspath()
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":extractSkillsJars")?.outcome)

        val skillMd = File(outputDir, "skillsjars__org__repo__skill/SKILL.md")
        assertTrue(skillMd.exists(), "SKILL.md should exist")
    }

    @Test
    fun `clean task deletes configured output directory`() {
        writeSettingsFile()
        writeBuildFile(
            extensionConfig = """
                skillsjars {
                    outputDir.set(layout.projectDirectory.dir("my-extracted-skills"))
                }
            """.trimIndent()
        )

        val outputDir = File(projectDir, "my-extracted-skills")
        outputDir.mkdirs()
        File(outputDir, "dummy.txt").writeText("dummy")
        assertTrue(outputDir.exists())

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("clean")
            .withPluginClasspath()
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":clean")?.outcome)
        assertFalse(outputDir.exists(), "Clean task should delete configured outputDir")
    }

    private fun writeSettingsFile() {
        File(projectDir, "settings.gradle.kts").writeText("")
    }

    private fun writeBuildFile(extensionConfig: String = "", dependencies: String = "") {
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                java
                id("com.skillsjars.gradle-plugin")
            }

            $extensionConfig

            repositories {
                maven { url = uri("repo") }
            }

            dependencies {
                $dependencies
            }
            """.trimIndent()
        )
    }

    private fun setupLocalRepo(artifactId: String, group: String = "com.skillsjars", skillsPrefix: String = "META-INF/resources/skills/") {
        val groupPath = group.replace(".", "/")
        val artifactDir = File(projectDir, "repo/$groupPath/$artifactId/1.0.0")
        artifactDir.mkdirs()

        createTestSkillsJar(File(artifactDir, "$artifactId-1.0.0.jar"), skillsPrefix)

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

    private fun createTestSkillsJar(file: File, skillsPrefix: String = "META-INF/resources/skills/") {
        JarOutputStream(FileOutputStream(file)).use { jos ->
            // Add SKILL.md marker
            jos.putNextEntry(JarEntry("${skillsPrefix}org/repo/skill/SKILL.md"))
            jos.write("# Test Skill".toByteArray())
            jos.closeEntry()

            // Add file at root of skill
            jos.putNextEntry(JarEntry("${skillsPrefix}org/repo/skill/test.txt"))
            jos.write("test content".toByteArray())
            jos.closeEntry()

            // Add nested file
            jos.putNextEntry(JarEntry("${skillsPrefix}org/repo/skill/foo/nested.txt"))
            jos.write("nested content".toByteArray())
            jos.closeEntry()
        }
    }
}
