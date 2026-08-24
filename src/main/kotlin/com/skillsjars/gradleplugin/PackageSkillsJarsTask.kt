package com.skillsjars.gradleplugin

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import java.util.regex.Pattern

/**
 * Task that packages local skill directories into `META-INF/skills/...` resource files.
 *
 * It reads skill directories from [sourceDir] (default `skills/`), validates that each directory
 * contains a `SKILL.md` file, verifies that any `allowed-tools` frontmatter matches build configuration,
 * and copies the skills into [outputDir] structured by GitHub coordinates (e.g. `META-INF/skills/<org>/<repo>/...`)
 * or group path fallback (e.g. `META-INF/skills/<groupPath>/...`).
 */
abstract class PackageSkillsJarsTask : DefaultTask() {

    companion object {
        private const val PROPERTY_PREFIX = "skillsjars.skill."
        private val GITHUB_URL_PATTERN = Pattern.compile(""".*github\.com[:/]([^/]+)/([^/]+?)(?:\.git)?/?$""")
    }

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    abstract val sourceDir: DirectoryProperty

    @get:Input
    @get:Optional
    abstract val projectGroup: Property<String>

    @get:Input
    @get:Optional
    abstract val gitHubUrl: Property<String>

    @get:Input
    @get:Optional
    abstract val allowedTools: MapProperty<String, String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun packageSkills() {
        val srcDir = sourceDir.orNull?.asFile
        val outDir = outputDir.get().asFile.toPath()

        val managedSkillsRoot = outDir.resolve("META-INF/skills")
        deleteDirectory(managedSkillsRoot)

        if (srcDir == null || !srcDir.isDirectory) {
            logger.debug("Skills directory not found: $srcDir")
            return
        }

        logger.info("Packaging skills from: $srcDir")

        val skillDirs = srcDir.listFiles { file -> file.isDirectory }?.sortedBy { it.name } ?: emptyList()
        if (skillDirs.isEmpty()) {
            logger.warn("No skill directories found in: $srcDir")
            return
        }

        val packageRoot = resolvePackageRoot()
        val targetRoot = outDir.resolve(packageRoot)
        Files.createDirectories(targetRoot)

        val allowedToolsMap = allowedTools.orNull ?: emptyMap()

        for (skillDir in skillDirs) {
            val skillMarker = File(skillDir, "SKILL.md")
            if (!skillMarker.exists()) {
                logger.warn("Skipping directory without SKILL.md: ${skillDir.name}")
                continue
            }

            validateAllowedTools(skillMarker.toPath(), allowedToolsMap)
            copySkill(skillDir.toPath(), targetRoot.resolve(skillDir.name))
        }

        logger.info("Skills packaged to: $targetRoot")
    }

    private fun resolvePackageRoot(): String {
        val ghUrl = gitHubUrl.orNull
        if (!ghUrl.isNullOrBlank()) {
            val matcher = GITHUB_URL_PATTERN.matcher(ghUrl)
            if (matcher.matches()) {
                val org = matcher.group(1)
                val repo = matcher.group(2)
                return "META-INF/skills/$org/$repo"
            }
        }

        val group = projectGroup.orNull ?: project.group.toString()
        val groupPath = group.replace('.', '/')
        return "META-INF/skills/$groupPath"
    }

    private fun copySkill(skillDir: Path, targetSkillRoot: Path) {
        Files.walk(skillDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { file ->
                val relative = skillDir.relativize(file)
                val targetFile = targetSkillRoot.resolve(relative.toString())
                Files.createDirectories(targetFile.parent)
                Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING)
                logger.debug("Copied: $targetFile")
            }
        }
    }

    private fun validateAllowedTools(skillMdFile: Path, allowedToolsBySkill: Map<String, String>) {
        val content = String(Files.readAllBytes(skillMdFile), StandardCharsets.UTF_8)
        val frontmatter = extractFrontmatter(content) ?: return

        val skillName = extractFrontmatterValue(frontmatter, "name")
        val declaredAllowedTools = extractFrontmatterValue(frontmatter, "allowed-tools")

        if (skillName != null && declaredAllowedTools != null) {
            val propertyName = "$PROPERTY_PREFIX$skillName.allowed-tools"
            val configured = allowedToolsBySkill[skillName]
            if (configured == null) {
                throw GradleException(
                    "SKILL.md for '$skillName' has allowed-tools but build is missing '$propertyName'. " +
                        "Add $propertyName to allowedTools."
                )
            } else if (configured != declaredAllowedTools) {
                throw GradleException(
                    "'$propertyName' value '$configured' does not match SKILL.md allowed-tools '$declaredAllowedTools'"
                )
            }
        }
    }

    private fun extractFrontmatter(content: String): String? {
        val lines = content.lines()
        if (lines.isEmpty() || lines[0].trim() != "---") return null
        val builder = StringBuilder()
        var index = 1
        while (index < lines.size && lines[index].trim() != "---") {
            builder.append(lines[index]).append('\n')
            index++
        }
        return if (index < lines.size) builder.toString() else null
    }

    private fun extractFrontmatterValue(frontmatter: String, key: String): String? {
        val pattern = Pattern.compile("^" + Pattern.quote(key) + ":\\s*(.+)$", Pattern.MULTILINE)
        val matcher = pattern.matcher(frontmatter)
        return if (matcher.find()) matcher.group(1).trim() else null
    }

    private fun deleteDirectory(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { p ->
                try {
                    Files.deleteIfExists(p)
                } catch (e: IOException) {
                    logger.warn("Failed to delete: $p")
                }
            }
        }
    }
}
