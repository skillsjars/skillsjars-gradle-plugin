package com.skillsjars.gradleplugin

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.io.FileOutputStream
import java.util.Properties
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
 * output directory precedence, group-agnostic extraction, collision detection, resolution failures,
 * clean task behavior, and reuse of configuration cache entries.
 */
class ExtractSkillsJarsTaskTest {

    private lateinit var projectDir: File

    @BeforeTest
    fun setUp() {
        projectDir = createTempDirectory("skillsjars-test").toFile()
        enableConfigurationCache(projectDir)
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
    fun `extract fails when a skill dependency does not resolve`() {
        setupLocalRepo("test-skill")
        writeSettingsFile()
        writeBuildFile(
            dependencies = """
                skill("com.skillsjars:test-skill:1.0.0")
                skill("com.skillsjars:missing-skill:1.0.0")
            """.trimIndent()
        )

        val outputDir = File(projectDir, "output")

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("extractSkillsJars", "-PoutputDir=${outputDir.absolutePath}")
            .withPluginClasspath()
            .buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":extractSkillsJars")?.outcome)
        assertTrue(
            result.output.contains("Could not find com.skillsjars:missing-skill:1.0.0"),
            "Should name the dependency that did not resolve"
        )
    }

    @Test
    fun `failed resolution keeps the skills extracted before`() {
        setupLocalRepo("test-skill")
        writeSettingsFile()
        writeBuildFile(
            dependencies = """skill("com.skillsjars:test-skill:1.0.0")"""
        )

        val outputDir = File(projectDir, "output")
        val skillMd = File(outputDir, "skillsjars__org__repo__skill/SKILL.md")

        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("extractSkillsJars", "-PoutputDir=${outputDir.absolutePath}")
            .withPluginClasspath()
            .build()

        assertTrue(skillMd.exists(), "SKILL.md should exist after the first extraction")

        writeBuildFile(
            dependencies = """
                skill("com.skillsjars:test-skill:1.0.0")
                skill("com.skillsjars:missing-skill:1.0.0")
            """.trimIndent()
        )

        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("extractSkillsJars", "-PoutputDir=${outputDir.absolutePath}")
            .withPluginClasspath()
            .buildAndFail()

        assertTrue(skillMd.exists(), "SKILL.md extracted before should be kept")
    }

    @Test
    fun `extract reuses the configuration cache entry`() {
        setupLocalRepo("test-skill")
        writeSettingsFile()
        writeBuildFile(
            extensionConfig = """
                skillsjars {
                    outputDir.set(layout.projectDirectory.dir("configured-output"))
                }
            """.trimIndent(),
            dependencies = """skill("com.skillsjars:test-skill:1.0.0")"""
        )

        val runner = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("extractSkillsJars")
            .withPluginClasspath()

        val first = runner.build()
        assertTrue(first.output.contains(CONFIGURATION_CACHE_STORED), "First run should store a configuration cache entry")

        val outputDir = File(projectDir, "configured-output")
        val staleSkill = File(outputDir, "skillsjars__org__repo__removed/SKILL.md")
        staleSkill.parentFile.mkdirs()
        staleSkill.writeText("# Removed skill")
        val keptSkill = File(outputDir, "my-skill/SKILL.md")
        keptSkill.parentFile.mkdirs()
        keptSkill.writeText("# A skill the project keeps")

        val second = runner.build()

        assertTrue(second.output.contains(CONFIGURATION_CACHE_REUSED), "Second run should reuse the configuration cache entry")
        assertEquals(TaskOutcome.SUCCESS, second.task(":extractSkillsJars")?.outcome)
        assertEquals("test content", File(outputDir, "skillsjars__org__repo__skill/test.txt").readText())
        assertEquals("nested content", File(outputDir, "skillsjars__org__repo__skill/foo/nested.txt").readText())
        assertFalse(staleSkill.parentFile.exists(), "Extracted skills that are gone should be removed when the entry is reused")
        assertEquals("# A skill the project keeps", keptSkill.readText(), "Other content of the output directory should be kept")
    }

    @Test
    fun `changed and removed skill dependencies are extracted with the configuration cache`() {
        setupLocalRepo("test-skill", content = "version 1")
        setupLocalRepo("test-skill", version = "2.0.0", content = "version 2")
        setupLocalRepo("other-skill", skillPath = "org/repo/other")
        writeSettingsFile()
        writeBuildFile(
            dependencies = """
                skill("com.skillsjars:test-skill:1.0.0")
                skill("com.skillsjars:other-skill:1.0.0")
            """.trimIndent()
        )

        val outputDir = File(projectDir, "output")
        val testFile = File(outputDir, "skillsjars__org__repo__skill/test.txt")
        val otherSkillDir = File(outputDir, "skillsjars__org__repo__other")

        val runner = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("extractSkillsJars", "-PoutputDir=${outputDir.absolutePath}")
            .withPluginClasspath()

        runner.build()

        assertEquals("version 1", testFile.readText())
        assertTrue(File(otherSkillDir, "SKILL.md").exists(), "other-skill should be extracted")

        writeBuildFile(
            dependencies = """skill("com.skillsjars:test-skill:2.0.0")"""
        )

        val changed = runner.build()

        assertTrue(changed.output.contains(CONFIGURATION_CACHE_STORED), "Changed dependencies should store a new entry")
        assertEquals("version 2", testFile.readText(), "Changed skill dependency should be extracted")
        assertFalse(otherSkillDir.exists(), "Removed skill dependency should no longer be extracted")

        val reused = runner.build()

        assertTrue(reused.output.contains(CONFIGURATION_CACHE_REUSED), "Unchanged build should reuse the entry")
        assertEquals("version 2", testFile.readText())
        assertFalse(otherSkillDir.exists(), "Removed skill dependency should no longer be extracted")
    }

    @Test
    fun `cli -P option changes are honored with the configuration cache`() {
        setupLocalRepo("test-skill")
        writeSettingsFile()
        writeBuildFile(
            dependencies = """skill("com.skillsjars:test-skill:1.0.0")"""
        )

        for (outputDirName in listOf("first-output", "second-output")) {
            val outputDir = File(projectDir, outputDirName)

            GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments("extractSkillsJars", "-PoutputDir=${outputDir.absolutePath}")
                .withPluginClasspath()
                .build()

            assertTrue(File(outputDir, "skillsjars__org__repo__skill/SKILL.md").exists(), "$outputDirName should be used")
        }
    }

    @Test
    fun `extract fails with a reused configuration cache entry when a skill dependency does not resolve`() {
        setupLocalRepo("test-skill")
        writeSettingsFile()
        writeBuildFile(
            dependencies = """
                skill("com.skillsjars:test-skill:1.0.0")
                skill("com.skillsjars:missing-skill:1.0.0")
            """.trimIndent()
        )

        val outputDir = File(projectDir, "output")
        val previousFile = File(outputDir, "previous.txt")
        outputDir.mkdirs()
        previousFile.writeText("previous")

        val runner = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("extractSkillsJars", "-PoutputDir=${outputDir.absolutePath}")
            .withPluginClasspath()

        val first = runner.buildAndFail()
        assertTrue(first.output.contains(CONFIGURATION_CACHE_STORED), "First run should store a configuration cache entry")

        val second = runner.buildAndFail()

        assertTrue(second.output.contains(CONFIGURATION_CACHE_REUSED), "Second run should reuse the configuration cache entry")
        assertEquals(TaskOutcome.FAILED, second.task(":extractSkillsJars")?.outcome)
        assertTrue(
            second.output.contains("Could not find com.skillsjars:missing-skill:1.0.0"),
            "Should name the dependency that did not resolve"
        )
        assertTrue(previousFile.exists(), "Output directory should be left as it was")
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
    fun `clean task deletes the extracted skills of the configured output directory`() {
        writeSettingsFile()
        writeBuildFile(
            extensionConfig = """
                skillsjars {
                    outputDir.set(layout.projectDirectory.dir("my-extracted-skills"))
                }
            """.trimIndent()
        )

        val outputDir = File(projectDir, "my-extracted-skills")
        val extractedSkill = File(outputDir, "skillsjars__org__repo__skill/SKILL.md")
        extractedSkill.parentFile.mkdirs()
        extractedSkill.writeText("# Extracted skill")
        val keptSkill = File(outputDir, "my-skill/SKILL.md")
        keptSkill.parentFile.mkdirs()
        keptSkill.writeText("# A skill the project keeps")

        val runner = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("clean")
            .withPluginClasspath()

        val kept = runner.build()

        assertEquals(TaskOutcome.SUCCESS, kept.task(":clean")?.outcome)
        assertFalse(extractedSkill.parentFile.exists(), "Clean task should delete the extracted skills")
        assertEquals("# A skill the project keeps", keptSkill.readText(), "Clean task should keep other content")

        keptSkill.parentFile.deleteRecursively()
        extractedSkill.parentFile.mkdirs()
        extractedSkill.writeText("# Extracted skill")

        val emptied = runner.build()

        assertTrue(emptied.output.contains(CONFIGURATION_CACHE_REUSED))
        assertFalse(outputDir.exists(), "Clean task should delete the output directory once nothing else is left in it")
    }

    @Test
    fun `cli -Pdir and dropping -PoutputDir are honored with the configuration cache`() {
        setupLocalRepo("test-skill")
        writeSettingsFile()
        writeBuildFile(
            extensionConfig = """
                skillsjars {
                    outputDir.set(layout.projectDirectory.dir("configured-output"))
                }
            """.trimIndent(),
            dependencies = """skill("com.skillsjars:test-skill:1.0.0")"""
        )

        val dirOutput = File(projectDir, "dir-output")
        val cliOutput = File(projectDir, "cli-output")
        val configuredOutput = File(projectDir, "configured-output")
        val skillMd = "skillsjars__org__repo__skill/SKILL.md"

        val stored = runner("extractSkillsJars", "-Pdir=${dirOutput.absolutePath}").build()
        val reused = runner("extractSkillsJars", "-Pdir=${dirOutput.absolutePath}").build()

        assertTrue(stored.output.contains(CONFIGURATION_CACHE_STORED))
        assertTrue(reused.output.contains(CONFIGURATION_CACHE_REUSED))
        assertTrue(File(dirOutput, skillMd).exists(), "-Pdir should be used")
        assertFalse(configuredOutput.exists(), "Extension output dir should not be used with -Pdir")

        runner("extractSkillsJars", "-PoutputDir=${cliOutput.absolutePath}").build()
        assertTrue(File(cliOutput, skillMd).exists(), "-PoutputDir should be used")

        runner("extractSkillsJars").build()

        assertTrue(File(configuredOutput, skillMd).exists(), "Extension output dir should be used once -PoutputDir is dropped")
    }

    @Test
    fun `a blank -PoutputDir falls back to -Pdir`() {
        setupLocalRepo("test-skill")
        writeSettingsFile()
        writeBuildFile(
            dependencies = """skill("com.skillsjars:test-skill:1.0.0")"""
        )

        val outputDir = File(projectDir, "dir-output")

        runner("extractSkillsJars", "-PoutputDir=", "-Pdir=${outputDir.absolutePath}").build()

        assertTrue(File(outputDir, "skillsjars__org__repo__skill/SKILL.md").exists(), "-Pdir should be used")
    }

    @Test
    fun `project properties named outputDir or dir are not taken for the command line option`() {
        setupLocalRepo("test-skill")
        writeSettingsFile()
        writeBuildFile(
            extensionConfig = """
                extra["outputDir"] = "extra-output"
                extra["dir"] = "extra-dir"

                skillsjars {
                    outputDir.set(layout.projectDirectory.dir("configured-output"))
                }
            """.trimIndent(),
            dependencies = """skill("com.skillsjars:test-skill:1.0.0")"""
        )

        runner("extractSkillsJars").build()

        assertTrue(File(projectDir, "configured-output/skillsjars__org__repo__skill/SKILL.md").exists())
        assertFalse(File(projectDir, "extra-output").exists(), "A project property should not override the output directory")
        assertFalse(File(projectDir, "extra-dir").exists(), "A project property should not override the output directory")
    }

    @Test
    fun `a file dependency is skipped with a warning`() {
        setupLocalRepo("test-skill")
        writeSettingsFile()
        File(projectDir, "libs").mkdirs()
        createTestSkillsJar(File(projectDir, "libs/local-skill.jar"), skillPath = "org/repo/local")
        writeBuildFile(
            dependencies = """
                skill("com.skillsjars:test-skill:1.0.0")
                skill(files("libs/local-skill.jar"))
            """.trimIndent()
        )

        val outputDir = File(projectDir, "output")

        val result = runner("extractSkillsJars", "-PoutputDir=${outputDir.absolutePath}").build()

        assertTrue(result.output.contains("Skipping local-skill.jar"), "The skipped file dependency should be named")
        assertTrue(File(outputDir, "skillsjars__org__repo__skill/SKILL.md").exists())
        assertFalse(File(outputDir, "skillsjars__org__repo__local").exists())
    }

    @Test
    fun `tasks given the artifacts of another configuration extract them`() {
        setupLocalRepo("test-skill")
        File(projectDir, "settings.gradle.kts").writeText("""include("early", "plain")""")
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                id("com.skillsjars.gradle-plugin") apply false
            }
            """.trimIndent()
        )
        // :early registers its task before it applies the plugin; :plain does not apply the plugin
        val applyPlugin = mapOf("early" to """apply(plugin = "com.skillsjars.gradle-plugin")""", "plain" to "")
        for ((name, apply) in applyPlugin) {
            File(projectDir, name).mkdirs()
            File(projectDir, "$name/build.gradle.kts").writeText(
                """
                import com.skillsjars.gradleplugin.ExtractSkillsJarsTask

                repositories {
                    maven { url = uri("${File(projectDir, "repo").toURI()}") }
                }

                val other by configurations.creating

                dependencies {
                    other("com.skillsjars:test-skill:1.0.0")
                }

                tasks.register<ExtractSkillsJarsTask>("extractOther") {
                    skillArtifacts.set(other.incoming.artifacts)
                    outputDir.set(layout.projectDirectory.dir("other-output"))
                }

                $apply
                """.trimIndent()
            )
        }

        val result = runner(":early:extractOther", ":plain:extractOther").build()

        assertFalse(result.output.contains("Skipping test-skill"), "The artifacts of the other configuration should be extracted")
        for (name in applyPlugin.keys) {
            assertEquals(TaskOutcome.SUCCESS, result.task(":$name:extractOther")?.outcome)
            assertTrue(
                File(projectDir, "$name/other-output/skillsjars__org__repo__skill/SKILL.md").exists(),
                ":$name should extract the artifacts it was given"
            )
        }
    }

    @Test
    fun `every jar of a dependency is extracted, such as one with a classifier`() {
        setupLocalRepo("test-skill")
        createTestSkillsJar(
            File(projectDir, "repo/com/skillsjars/test-skill/1.0.0/test-skill-1.0.0-extra.jar"),
            skillPath = "org/repo/extra"
        )
        writeSettingsFile()
        writeBuildFile(
            dependencies = """
                skill("com.skillsjars:test-skill:1.0.0")
                skill("com.skillsjars:test-skill:1.0.0:extra")
            """.trimIndent()
        )

        val outputDir = File(projectDir, "output")

        val result = runner("extractSkillsJars", "-PoutputDir=${outputDir.absolutePath}").build()

        assertTrue(result.output.contains("Found 2 SkillsJar(s)"))
        assertTrue(File(outputDir, "skillsjars__org__repo__skill/SKILL.md").exists())
        assertTrue(File(outputDir, "skillsjars__org__repo__extra/SKILL.md").exists(), "The classifier jar should be extracted")
    }

    @Test
    fun `a project dependency whose jar is not built is skipped with a warning`() {
        setupLocalRepo("test-skill")
        File(projectDir, "settings.gradle.kts").writeText("""include("skills")""")
        File(projectDir, "skills").mkdirs()
        File(projectDir, "skills/build.gradle.kts").writeText(
            """
            plugins { java }
            tasks.jar { enabled = false }
            """.trimIndent()
        )
        val projectSkill = File(projectDir, "skills/src/main/resources/META-INF/skills/org/repo/project/SKILL.md")
        projectSkill.parentFile.mkdirs()
        projectSkill.writeText("# Project skill")
        writeBuildFile(
            dependencies = """
                skill("com.skillsjars:test-skill:1.0.0")
                skill(project(":skills"))
            """.trimIndent()
        )

        val outputDir = File(projectDir, "output")

        val result = runner("extractSkillsJars", "-PoutputDir=${outputDir.absolutePath}").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":extractSkillsJars")?.outcome)
        assertTrue(result.output.contains("was not built"), "Should say why the project's skills are missing")
        assertTrue(result.output.contains("Found 1 SkillsJar(s)"))
        assertTrue(File(outputDir, "skillsjars__org__repo__skill/SKILL.md").exists(), "The other SkillsJars should be extracted")
    }

    @Test
    fun `modules that only a skipped transitive project brings in are skipped with it`() {
        setupLocalRepo("test-skill")
        File(projectDir, "settings.gradle.kts").writeText("""include("skills-a", "skills-b")""")
        File(projectDir, "skills-a").mkdirs()
        File(projectDir, "skills-a/build.gradle.kts").writeText(
            """
            plugins { java }
            dependencies { implementation(project(":skills-b")) }
            """.trimIndent()
        )
        File(projectDir, "skills-b").mkdirs()
        File(projectDir, "skills-b/build.gradle.kts").writeText(
            """
            plugins { java }
            dependencies { runtimeOnly("com.skillsjars:test-skill:1.0.0") }
            """.trimIndent()
        )
        val skillA = File(projectDir, "skills-a/src/main/resources/META-INF/skills/org/repo/a/SKILL.md")
        skillA.parentFile.mkdirs()
        skillA.writeText("# A")
        writeBuildFile(dependencies = """skill(project(":skills-a"))""")

        val outputDir = File(projectDir, "output")
        val moduleSkill = File(outputDir, "skillsjars__org__repo__skill/SKILL.md")

        val skipped = runner("extractSkillsJars", "-PoutputDir=${outputDir.absolutePath}").build()

        assertTrue(skipped.output.contains("Found 1 SkillsJar(s)"))
        assertTrue(
            skipped.output.contains(
                "Skipped 1 transitive project dependency: only projects declared with skill(project(...)) are extracted."
            ),
            "The skipped project should be reported without --info"
        )
        assertTrue(skipped.output.contains("Run with --info to see which"))
        assertTrue(File(outputDir, "skillsjars__org__repo__a/SKILL.md").exists())
        assertFalse(moduleSkill.exists(), "A module only the skipped project brings in should not be extracted")

        writeBuildFile(
            dependencies = """
                skill(project(":skills-a"))
                skill("com.skillsjars:test-skill:1.0.0")
            """.trimIndent()
        )

        val declared = runner("extractSkillsJars", "-PoutputDir=${outputDir.absolutePath}").build()

        assertTrue(declared.output.contains("Found 2 SkillsJar(s)"))
        assertTrue(moduleSkill.exists(), "A module declared directly should be extracted")
    }

    @Test
    fun `two projects extracting to the same directory fail instead of clearing each other's skills`() {
        setupLocalRepo("test-skill")
        File(projectDir, "settings.gradle.kts").writeText("""include("first", "second")""")
        File(projectDir, "build.gradle.kts").writeText("")
        writeExtractingProject("first")
        writeExtractingProject("second")

        val outputDir = File(projectDir, "shared-output")

        val result = runner("extractSkillsJars", "-PoutputDir=${outputDir.absolutePath}").buildAndFail()

        assertTrue(result.output.contains("both extract SkillsJars to"), "Should name the tasks sharing the directory")
    }

    @Test
    fun `a project extracting into a skill folder of another fails instead of deleting its skills`() {
        setupLocalRepo("test-skill")
        File(projectDir, "settings.gradle.kts").writeText("""include("outer", "inner", "insideSkill")""")
        File(projectDir, "build.gradle.kts").writeText("")
        writeExtractingProject("outer", outputDir = "../agents")
        writeExtractingProject("inner", outputDir = "../agents/skills")
        writeExtractingProject("insideSkill", outputDir = "../agents/skillsjars__org__repo__skill/more")

        val nested = runner(":outer:extractSkillsJars", ":inner:extractSkillsJars").build()

        assertEquals(TaskOutcome.SUCCESS, nested.task(":outer:extractSkillsJars")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, nested.task(":inner:extractSkillsJars")?.outcome)
        assertTrue(File(projectDir, "agents/skillsjars__org__repo__skill/SKILL.md").exists())
        assertTrue(
            File(projectDir, "agents/skills/skillsjars__org__repo__skill/SKILL.md").exists(),
            "A directory inside another's, but not inside one of its skill folders, should keep its skills"
        )

        val insideSkill = runner(":outer:extractSkillsJars", ":insideSkill:extractSkillsJars").buildAndFail()

        assertTrue(
            Regex(":(outer|insideSkill):extractSkillsJars extracts SkillsJars to").containsMatchIn(insideSkill.output),
            "Should name the tasks"
        )
        assertTrue(insideSkill.output.contains("would delete the SkillsJars of the other"))
    }

    @Test
    fun `tasks of an included build sharing a directory are named by their paths in the build tree`() {
        setupLocalRepo("test-skill")
        File(projectDir, "settings.gradle.kts").writeText("""includeBuild("other")""")
        File(projectDir, "build.gradle.kts").writeText("")
        File(projectDir, "other").mkdirs()
        File(projectDir, "other/settings.gradle.kts").writeText("""include("first", "second")""")
        writeExtractingProject("other/first")
        writeExtractingProject("other/second")

        val outputDir = File(projectDir, "shared-output")

        val result = runner(
            ":other:first:extractSkillsJars", ":other:second:extractSkillsJars", "-PoutputDir=${outputDir.absolutePath}"
        ).buildAndFail()

        assertTrue(
            Regex(":other:(first|second):extractSkillsJars and :other:(first|second):extractSkillsJars both extract")
                .containsMatchIn(result.output),
            "Should name the tasks by the paths that run them from the root build"
        )
        assertTrue(Regex("for example ./gradlew :other:(first|second):extractSkillsJars").containsMatchIn(result.output))
    }

    @Test
    fun `projects loading the plugin with class loaders of their own check their directories against each other`() {
        setupLocalRepo("test-skill")
        File(projectDir, "settings.gradle.kts").writeText("""include("first", "second")""")
        File(projectDir, "build.gradle.kts").writeText("")
        writeExtractingProject("first")
        writeProjectApplyingPluginFromClasspath("second")

        val separate = runner("extractSkillsJars", "-PoutputDir=skills-output").build()

        assertEquals(TaskOutcome.SUCCESS, separate.task(":first:extractSkillsJars")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, separate.task(":second:extractSkillsJars")?.outcome)

        val outputDir = File(projectDir, "shared-output")

        val shared = runner("extractSkillsJars", "-PoutputDir=${outputDir.absolutePath}").buildAndFail()

        assertTrue(shared.output.contains("both extract SkillsJars to"), "Should name the tasks sharing the directory")
    }

    @Test
    fun `tasks of different builds of a composite build sharing a directory fail`() {
        setupLocalRepo("test-skill")
        File(projectDir, "settings.gradle.kts").writeText(
            """
            include("app")
            includeBuild("other")
            """.trimIndent()
        )
        File(projectDir, "build.gradle.kts").writeText("")
        writeExtractingProject("app")
        File(projectDir, "other").mkdirs()
        File(projectDir, "other/settings.gradle.kts").writeText("")
        writeProjectApplyingPluginFromClasspath("other")

        val outputDir = File(projectDir, "shared-output")
        val tasks = arrayOf(":app:extractSkillsJars", ":other:extractSkillsJars")
        val bothTasksShareTheDirectory = Regex(
            ":(app|other):extractSkillsJars and :(app|other):extractSkillsJars both extract SkillsJars to"
        )

        val stored = runner(*tasks, "-PoutputDir=${outputDir.absolutePath}").buildAndFail()

        assertTrue(stored.output.contains(CONFIGURATION_CACHE_STORED))
        assertTrue(bothTasksShareTheDirectory.containsMatchIn(stored.output), "Should name the tasks of both builds")

        val reused = runner(*tasks, "-PoutputDir=${outputDir.absolutePath}").buildAndFail()

        assertTrue(reused.output.contains(CONFIGURATION_CACHE_REUSED))
        assertTrue(bothTasksShareTheDirectory.containsMatchIn(reused.output), "Should name the tasks of both builds")

        val separate = runner(*tasks, "-PoutputDir=skills-output").build()

        assertEquals(TaskOutcome.SUCCESS, separate.task(":app:extractSkillsJars")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, separate.task(":other:extractSkillsJars")?.outcome)
    }

    @Test
    fun `a task registered where the plugin is not applied runs without SkillsJars`() {
        File(projectDir, "settings.gradle.kts").writeText("""include("sub")""")
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                id("com.skillsjars.gradle-plugin") apply false
            }
            """.trimIndent()
        )
        File(projectDir, "sub").mkdirs()
        File(projectDir, "sub/build.gradle.kts").writeText(
            """
            import com.skillsjars.gradleplugin.ExtractSkillsJarsTask

            tasks.register<ExtractSkillsJarsTask>("myExtract") {
                outputDir.set(layout.projectDirectory.dir("out"))
            }
            """.trimIndent()
        )

        val result = runner(":sub:myExtract").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":sub:myExtract")?.outcome)
        assertTrue(result.output.contains("Found 0 SkillsJar(s)"))
        assertTrue(File(projectDir, "sub/out").isDirectory)

        val cliOutput = File(projectDir, "cli-output")

        runner(":sub:myExtract", "-PoutputDir=${cliOutput.absolutePath}").build()

        assertTrue(cliOutput.isDirectory, "-PoutputDir should apply to a task registered without the plugin")
    }

    @Test
    fun `extract and fail on an unresolved dependency without the configuration cache`() {
        setupLocalRepo("test-skill")
        writeSettingsFile()
        writeBuildFile(
            dependencies = """skill("com.skillsjars:test-skill:1.0.0")"""
        )

        val outputDir = File(projectDir, "output")
        val skillMd = File(outputDir, "skillsjars__org__repo__skill/SKILL.md")

        val extracted = runner("extractSkillsJars", "-PoutputDir=${outputDir.absolutePath}", "--no-configuration-cache").build()

        assertFalse(extracted.output.contains("Configuration cache entry"), "The build should run without the configuration cache")
        assertEquals("test content", File(outputDir, "skillsjars__org__repo__skill/test.txt").readText())

        writeBuildFile(
            dependencies = """
                skill("com.skillsjars:test-skill:1.0.0")
                skill("com.skillsjars:missing-skill:1.0.0")
            """.trimIndent()
        )

        val failed = runner("extractSkillsJars", "-PoutputDir=${outputDir.absolutePath}", "--no-configuration-cache").buildAndFail()

        assertEquals(TaskOutcome.FAILED, failed.task(":extractSkillsJars")?.outcome)
        assertTrue(failed.output.contains("Could not find com.skillsjars:missing-skill:1.0.0"))
        assertTrue(skillMd.exists(), "SKILL.md extracted before should be kept")
    }

    @Test
    fun `extract transitive module dependencies substituted from an included build`() {
        setupLocalRepo(
            "published-skill",
            group = "org.example",
            dependencies = """
                <dependency>
                    <groupId>org.example</groupId>
                    <artifactId>included-skills</artifactId>
                    <version>1.0.0</version>
                </dependency>
            """.trimIndent(),
        )
        File(projectDir, "settings.gradle.kts").writeText("""includeBuild("included")""")
        val includedDir = File(projectDir, "included")
        includedDir.mkdirs()
        File(includedDir, "settings.gradle.kts").writeText("""rootProject.name = "included-skills"""")
        File(includedDir, "build.gradle.kts").writeText(
            """
            plugins { java }
            group = "org.example"
            version = "1.0.0"
            """.trimIndent()
        )
        val includedSkill = File(includedDir, "src/main/resources/META-INF/skills/org/example/included/SKILL.md")
        includedSkill.parentFile.mkdirs()
        includedSkill.writeText("# Included skill")
        writeBuildFile(dependencies = """skill("org.example:published-skill:1.0.0")""")

        val runner = runner("extractSkillsJars", "-PoutputDir=output", "--info")
        val first = runner.build()

        assertTrue(first.output.contains(CONFIGURATION_CACHE_STORED))
        assertEquals(TaskOutcome.SUCCESS, first.task(":included:jar")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, first.task(":extractSkillsJars")?.outcome)
        assertTrue(first.output.contains("Found 2 SkillsJar(s)"))
        assertTrue(File(projectDir, "output/skillsjars__org__repo__skill/SKILL.md").exists())
        val extractedSkill = File(projectDir, "output/skillsjars__org__example__included/SKILL.md")
        assertEquals("# Included skill", extractedSkill.readText())

        includedSkill.writeText("# Updated included skill")

        val reused = runner.build()

        assertTrue(reused.output.contains(CONFIGURATION_CACHE_REUSED))
        assertEquals(TaskOutcome.SUCCESS, reused.task(":included:jar")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, reused.task(":extractSkillsJars")?.outcome)
        assertTrue(reused.output.contains("Found 2 SkillsJar(s)"))
        assertTrue(File(projectDir, "output/skillsjars__org__repo__skill/SKILL.md").exists())
        assertEquals("# Updated included skill", extractedSkill.readText())
    }

    @Test
    fun `extract the projects an included build module depends on in that build`() {
        File(projectDir, "settings.gradle.kts").writeText("""includeBuild("included")""")
        val includedDir = File(projectDir, "included")
        includedDir.mkdirs()
        File(includedDir, "settings.gradle.kts").writeText("""include("skills-a", "skills-b")""")
        for ((name, dependencies) in listOf("skills-a" to """implementation(project(":skills-b"))""", "skills-b" to "")) {
            File(includedDir, name).mkdirs()
            File(includedDir, "$name/build.gradle.kts").writeText(
                """
                plugins { java }
                group = "org.example"
                version = "1.0.0"
                dependencies { $dependencies }
                """.trimIndent()
            )
            val skill = File(includedDir, "$name/src/main/resources/META-INF/skills/org/example/$name/SKILL.md")
            skill.parentFile.mkdirs()
            skill.writeText("# $name")
        }
        writeBuildFile(dependencies = """skill("org.example:skills-a:1.0.0")""")

        val runner = runner("extractSkillsJars", "-PoutputDir=output", "--info")
        val first = runner.build()

        assertTrue(first.output.contains(CONFIGURATION_CACHE_STORED))
        assertEquals(TaskOutcome.SUCCESS, first.task(":included:skills-b:jar")?.outcome)
        assertTrue(first.output.contains("Found 2 SkillsJar(s)"))
        assertFalse(first.output.contains("transitive project dependencies are not extracted"))
        assertEquals("# skills-a", File(projectDir, "output/skillsjars__org__example__skills-a/SKILL.md").readText())
        assertEquals("# skills-b", File(projectDir, "output/skillsjars__org__example__skills-b/SKILL.md").readText())

        val reused = runner.build()

        assertTrue(reused.output.contains(CONFIGURATION_CACHE_REUSED))
        assertTrue(reused.output.contains("Found 2 SkillsJar(s)"))
        assertTrue(File(projectDir, "output/skillsjars__org__example__skills-b/SKILL.md").exists())
    }

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments(*arguments)
            .withPluginClasspath()

    private fun writeSettingsFile() {
        File(projectDir, "settings.gradle.kts").writeText("")
    }

    private fun writeExtractingProject(name: String, outputDir: String? = null) {
        val extensionConfig = outputDir?.let {
            """skillsjars { outputDir.set(layout.projectDirectory.dir("$it")) }"""
        }.orEmpty()
        File(projectDir, name).mkdirs()
        File(projectDir, "$name/build.gradle.kts").writeText(
            """
            plugins {
                id("com.skillsjars.gradle-plugin")
            }

            $extensionConfig

            repositories {
                maven { url = uri("${File(projectDir, "repo").toURI()}") }
            }

            dependencies {
                skill("com.skillsjars:test-skill:1.0.0")
            }
            """.trimIndent()
        )
    }

    /**
     * Writes a project that applies the plugin from a buildscript classpath with one more entry than the plugin's own,
     * so it loads the plugin with a class loader of its own, as a project whose `plugins {}` block applies other
     * plugins does.
     */
    private fun writeProjectApplyingPluginFromClasspath(name: String) {
        val metadata = Properties()
        javaClass.classLoader.getResourceAsStream("plugin-under-test-metadata.properties")!!.use { metadata.load(it) }
        val otherPlugin = File(projectDir, "$name/other-plugin")
        otherPlugin.mkdirs()
        val classpath = (metadata.getProperty("implementation-classpath").split(File.pathSeparator) + otherPlugin.path)
            .joinToString { "\"${it.replace('\\', '/')}\"" }
        File(projectDir, "$name/build.gradle.kts").writeText(
            """
            buildscript {
                dependencies {
                    classpath(files($classpath))
                }
            }

            apply(plugin = "com.skillsjars.gradle-plugin")

            repositories {
                maven { url = uri("${File(projectDir, "repo").toURI()}") }
            }

            dependencies {
                "skill"("com.skillsjars:test-skill:1.0.0")
            }
            """.trimIndent()
        )
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

    private fun setupLocalRepo(
        artifactId: String,
        group: String = "com.skillsjars",
        skillsPrefix: String = "META-INF/resources/skills/",
        version: String = "1.0.0",
        skillPath: String = "org/repo/skill",
        content: String = "test content",
        dependencies: String = "",
    ) {
        val groupPath = group.replace(".", "/")
        val artifactDir = File(projectDir, "repo/$groupPath/$artifactId/$version")
        artifactDir.mkdirs()

        createTestSkillsJar(File(artifactDir, "$artifactId-$version.jar"), skillsPrefix, skillPath, content)

        File(artifactDir, "$artifactId-$version.pom").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>$group</groupId>
                <artifactId>$artifactId</artifactId>
                <version>$version</version>
                <dependencies>
                    ${dependencies.replace("\n", "\n                    ")}
                </dependencies>
            </project>
            """.trimIndent()
        )
    }

    private fun createTestSkillsJar(
        file: File,
        skillsPrefix: String = "META-INF/resources/skills/",
        skillPath: String = "org/repo/skill",
        content: String = "test content",
    ) {
        JarOutputStream(FileOutputStream(file)).use { jos ->
            // Add SKILL.md marker
            jos.putNextEntry(JarEntry("$skillsPrefix$skillPath/SKILL.md"))
            jos.write("# Test Skill".toByteArray())
            jos.closeEntry()

            // Add file at root of skill
            jos.putNextEntry(JarEntry("$skillsPrefix$skillPath/test.txt"))
            jos.write(content.toByteArray())
            jos.closeEntry()

            // Add nested file
            jos.putNextEntry(JarEntry("$skillsPrefix$skillPath/foo/nested.txt"))
            jos.write("nested content".toByteArray())
            jos.closeEntry()
        }
    }
}
