package com.skillsjars.gradleplugin

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.collections.iterator

abstract class ExtractSkillsJarsTask : DefaultTask() {

    companion object {
        const val SKILLSJARS_GROUP = "com.skillsjars"
        const val SKILLS_PREFIX = "META-INF/resources/skills/"
    }

    @get:Input
    abstract val dir: Property<String>

    @get:Input
    @get:Optional
    abstract val configurations: ListProperty<String>

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun extract() {
        if (!dir.isPresent || dir.get().isBlank()) {
            throw GradleException("The 'dir' parameter is required. Use -Pdir=<path>")
        }

        val outputPath = File(dir.get()).toPath()

        logger.lifecycle("Extracting SkillsJars to: ${dir.get()}")

        deleteDirectory(outputPath)
        Files.createDirectories(outputPath)

        val skillsJarFiles = findSkillsJars()
        logger.lifecycle("Found ${skillsJarFiles.size} SkillsJar(s)")

        val extractedPaths = mutableMapOf<String, String>()

        for ((artifactName, jarFile) in skillsJarFiles) {
            extractSkillsJar(artifactName, jarFile, outputPath, extractedPaths)
        }

        logger.lifecycle("Successfully extracted SkillsJars")
    }

    private fun findSkillsJars(): Map<String, File> {
        val result = mutableMapOf<String, File>()

        val configs = if (configurations.isPresent && configurations.get().isNotEmpty()) {
            configurations.get().mapNotNull { name ->
                project.configurations.findByName(name)?.takeIf { it.isCanBeResolved }
            }
        } else {
            project.configurations.filter { it.isCanBeResolved }
        }

        for (config in configs) {
            try {
                config.resolvedConfiguration.resolvedArtifacts
                    .filter { it.moduleVersion.id.group == SKILLSJARS_GROUP }
                    .forEach { artifact ->
                        val key = "${artifact.moduleVersion.id}"
                        result.putIfAbsent(key, artifact.file)
                    }
            } catch (e: Exception) {
                logger.debug("Could not resolve configuration ${config.name}: ${e.message}")
            }
        }

        return result
    }

    private fun extractSkillsJar(
        artifactName: String,
        jarFile: File,
        outputPath: Path,
        extractedPaths: MutableMap<String, String>,
    ) {
        if (!jarFile.exists()) {
            logger.warn("Artifact file not found: $artifactName")
            return
        }

        logger.lifecycle("Extracting: $artifactName")

        // First pass: find SKILL.md files to identify skill roots
        val skillRoots = mutableMapOf<String, String>()
        JarFile(jarFile).use { jar ->
            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val entryName = entry.name

                if (entryName.startsWith(SKILLS_PREFIX) && entryName.endsWith("/SKILL.md")) {
                    val relativePath = entryName.substring(SKILLS_PREFIX.length)
                    val skillRoot = relativePath.substring(0, relativePath.length - "/SKILL.md".length)
                    val flattenedRoot = skillRoot.replace("/", "__")
                    skillRoots["$skillRoot/"] = flattenedRoot
                }
            }
        }

        // Second pass: extract files using the skill roots
        JarFile(jarFile).use { jar ->
            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val entryName = entry.name

                if (!entryName.startsWith(SKILLS_PREFIX) || entry.isDirectory) continue

                val relativePath = entryName.substring(SKILLS_PREFIX.length)

                // Find the skill root for this file
                val rootEntry = skillRoots.entries.firstOrNull { relativePath.startsWith(it.key) }
                if (rootEntry == null) {
                    logger.warn("Skipping file not under a SKILL.md root: $relativePath")
                    continue
                }

                val (skillRoot, flattenedRoot) = rootEntry
                val remainder = relativePath.substring(skillRoot.length)
                val targetPath = outputPath.resolve("skillsjars__$flattenedRoot").resolve(remainder)

                val conflictKey = "skillsjars__$flattenedRoot/$remainder"
                val existing = extractedPaths[conflictKey]
                if (existing != null) {
                    throw GradleException(
                        "Path conflict detected: $conflictKey exists in both $existing and $artifactName"
                    )
                }

                extractedPaths[conflictKey] = artifactName

                Files.createDirectories(targetPath.parent)
                jar.getInputStream(entry).use { input ->
                    Files.copy(input, targetPath)
                }

                logger.debug("Extracted: $conflictKey")
            }
        }
    }

    private fun deleteDirectory(path: Path) {
        if (!Files.exists(path)) return

        Files.walk(path)
            .sorted(Comparator.reverseOrder())
            .forEach { p ->
                try {
                    Files.delete(p)
                } catch (e: IOException) {
                    logger.warn("Failed to delete: $p")
                }
            }
    }
}
