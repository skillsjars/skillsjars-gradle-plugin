package com.skillsjars.gradleplugin

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.io.FileOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests of [ExtractSkillsJarsTask] for states a TestKit build cannot reach, such as a SkillsJar file that disappears
 * after its dependency was resolved.
 */
class ExtractSkillsJarsTaskActionTest {

    private lateinit var projectDir: File

    @BeforeTest
    fun setUp() {
        projectDir = createTempDirectory("skillsjars-action-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        projectDir.deleteRecursively()
    }

    @Test
    fun `a SkillsJar file missing when the task runs fails before the output directory is cleared`() {
        val artifactDir = File(projectDir, "repo/com/skillsjars/test-skill/1.0.0")
        artifactDir.mkdirs()
        val jar = File(artifactDir, "test-skill-1.0.0.jar")
        JarOutputStream(FileOutputStream(jar)).use { jos ->
            jos.putNextEntry(JarEntry("META-INF/skills/org/repo/skill/SKILL.md"))
            jos.write("# Test Skill".toByteArray())
            jos.closeEntry()
        }
        File(artifactDir, "test-skill-1.0.0.pom").writeText(
            """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.skillsjars</groupId>
                <artifactId>test-skill</artifactId>
                <version>1.0.0</version>
            </project>
            """.trimIndent()
        )

        val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        project.plugins.apply("com.skillsjars.gradle-plugin")
        project.repositories.maven { url = project.uri("repo") }
        project.dependencies.add(ExtractSkillsJarsTask.SKILL_CONFIGURATION_NAME, "com.skillsjars:test-skill:1.0.0")

        val outputDir = File(projectDir, "output")
        val previousFile = File(outputDir, "previous.txt")
        outputDir.mkdirs()
        previousFile.writeText("previous")

        val task = project.tasks.named("extractSkillsJars", ExtractSkillsJarsTask::class.java).get()
        task.outputDir.set(outputDir)

        // resolves the artifact, which the task then reuses
        task.skillArtifacts.get().artifacts
        assertTrue(jar.delete())

        val failure = assertFailsWith<GradleException> { task.extract() }

        assertTrue(failure.message!!.contains("SkillsJar file not found for com.skillsjars:test-skill:1.0.0"), failure.message)
        assertTrue(previousFile.exists(), "Output directory should be left as it was")
    }
}
