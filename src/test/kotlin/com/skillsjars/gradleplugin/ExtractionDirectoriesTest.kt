package com.skillsjars.gradleplugin

import org.gradle.api.GradleException
import org.gradle.api.services.BuildServiceParameters
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for [ExtractionDirectories] claiming the directories of extraction tasks.
 */
class ExtractionDirectoriesTest {

    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("skillsjars-directories-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `a directory reached through a symbolic link is the directory it links to`() {
        val real = File(tempDir, "real")
        real.mkdirs()
        val link = File(tempDir, "link")
        assumeTrue(
            runCatching { Files.createSymbolicLink(link.toPath(), real.toPath()) }.isSuccess,
            "Symbolic links cannot be created here"
        )
        val directories = extractionDirectories()

        directories.accept(File(real, "skills"), ":first:extractSkillsJars")
        val failure = assertFailsWith<GradleException> {
            directories.accept(File(link, "skills"), ":second:extractSkillsJars")
        }

        assertTrue(failure.message!!.contains("both extract SkillsJars to"), failure.message)
    }

    @Test
    fun `sibling directories whose names share a prefix do not overlap`() {
        val directories = extractionDirectories()

        directories.accept(File(tempDir, "agents"), ":first:extractSkillsJars")
        directories.accept(File(tempDir, "agents-other"), ":second:extractSkillsJars")
    }

    @Test
    fun `a directory inside another that is not in one of its skill folders does not overlap`() {
        val directories = extractionDirectories()

        directories.accept(File(tempDir, "agents"), ":first:extractSkillsJars")
        directories.accept(File(tempDir, "agents/skills"), ":second:extractSkillsJars")
    }

    @Test
    fun `a directory inside a skill folder of another overlaps it, in either order`() {
        val outer = File(tempDir, "agents")
        val insideSkill = File(tempDir, "agents/skillsjars__org__repo__skill/more")

        val outerFirst = extractionDirectories()
        outerFirst.accept(outer, ":outer:extractSkillsJars")
        assertFailsWith<GradleException> { outerFirst.accept(insideSkill, ":inner:extractSkillsJars") }

        val innerFirst = extractionDirectories()
        innerFirst.accept(insideSkill, ":inner:extractSkillsJars")
        assertFailsWith<GradleException> { innerFirst.accept(outer, ":outer:extractSkillsJars") }
    }

    private fun extractionDirectories() = object : ExtractionDirectories() {
        override fun getParameters(): BuildServiceParameters.None = throw UnsupportedOperationException()
    }
}
