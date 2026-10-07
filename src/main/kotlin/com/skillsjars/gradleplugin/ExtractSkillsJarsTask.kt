package com.skillsjars.gradleplugin

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.services.BuildService
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.function.BiConsumer
import java.util.jar.JarFile
import javax.inject.Inject

/**
 * Task that extracts SkillsJar artifacts from the `skill` configuration into a specified target directory.
 *
 * It scans all resolved dependencies in the `skill` configuration for skill content located under
 * `META-INF/skills/` or `META-INF/resources/skills/`, flattens the skill roots into `skillsjars__<root>`,
 * replaces the `skillsjars__` folders of the target directory, leaving anything else in it, and checks for path
 * collisions between dependencies.
 * A dependency that fails to resolve, or the missing file of a module SkillsJar, fails the task before the target
 * directory is cleared. A project dependency whose jar was not built is skipped with a warning.
 */
@DisableCachingByDefault(because = "Extracts into a directory that is not a tracked task output")
abstract class ExtractSkillsJarsTask : DefaultTask() {

    companion object {
        const val SKILL_CONFIGURATION_NAME = "skill"
        val SKILLS_PREFIXES = listOf("META-INF/skills/", "META-INF/resources/skills/")
        private const val EXTRACTION_DIRECTORIES_SERVICE_NAME = "skillsJarsExtractionDirectories"
    }

    @get:Internal
    abstract val outputDir: DirectoryProperty

    /**
     * The output directory given on the command line with `-PoutputDir` (or `-Pdir`), which takes precedence over [outputDir].
     * Only Gradle properties are read, so a project property or extension of the same name is not mistaken for it.
     */
    @get:Internal
    abstract val outputDirOverride: DirectoryProperty

    /**
     * The artifacts to extract, which the plugin takes from the `skill` configuration. Without a value, there is
     * nothing to extract. A dependency that does not resolve fails the task when it runs. Only the artifacts of
     * module and project components are extracted.
     */
    @get:Internal
    abstract val skillArtifacts: Property<ArtifactCollection>

    /**
     * The root of the resolved dependency graph that [skillArtifacts] come from, which names each SkillsJar by its
     * module coordinates and tells which transitive project dependencies are left out. Without a value, or when the
     * artifacts do not come from it, SkillsJars are named by their components.
     */
    @get:Internal
    abstract val skillRootComponent: Property<ResolvedComponentResult>

    /**
     * The [ExtractionDirectories] of the build tree, so that two extraction tasks never replace each other's
     * SkillsJars. It is held as a [BuildService] and called as a [BiConsumer], because its class may come from another
     * class loader than this task's.
     */
    @get:Internal
    abstract val extractionDirectories: Property<BuildService<*>>

    @get:Inject
    protected abstract val providers: ProviderFactory

    @get:Inject
    protected abstract val layout: ProjectLayout

    @get:Inject
    protected abstract val objects: ObjectFactory

    /**
     * The path of this task in the build tree, such as `:other:sub:extractSkillsJars` for a task of an included build,
     * which names the task to run from the root build.
     */
    private val buildTreeTaskPath: String

    init {
        outputDirOverride.convention(
            layout.projectDirectory.dir(commandLineProperty("outputDir").orElse(commandLineProperty("dir")))
        )
        // Registered on the root build, so the tasks of every build of a composite build claim from the same one
        val rootBuild = generateSequence(project.gradle) { it.parent }.last()
        val directories = rootBuild.sharedServices.registerIfAbsent(
            EXTRACTION_DIRECTORIES_SERVICE_NAME, ExtractionDirectories::class.java
        ) {}
        extractionDirectories.set(directories)
        usesService(directories)
        buildTreeTaskPath = project.buildTreePath.removeSuffix(":") + ":" + name
        // Gradle builds the artifacts first. They are not task inputs: the extraction always runs, since it replaces
        // the SkillsJars in the output directory
        dependsOn(skillArtifacts.map { it.artifactFiles }.orElse(objects.fileCollection()))
    }

    @TaskAction
    fun extract() {
        val directory = (outputDirOverride.orNull ?: outputDir.orNull)?.asFile
            ?: throw GradleException(
                "An output directory is required. Use -PoutputDir=<path> or set outputDir in the skillsjars extension."
            )
        @Suppress("UNCHECKED_CAST")
        (extractionDirectories.get() as BiConsumer<File, String>).accept(directory, buildTreeTaskPath)
        val outputPath = directory.toPath()

        logger.lifecycle("Extracting SkillsJars to: $outputPath")

        val (built, notBuilt) = findSkillsJars().partition { it.file.exists() }
        val missingModules = notBuilt.filterNot { it.fromProject }
        if (missingModules.isNotEmpty()) {
            throw GradleException(
                "SkillsJar file not found for ${missingModules.joinToString { it.name }}: " +
                    "${missingModules.joinToString { it.file.path }}. $outputPath was left as it was."
            )
        }
        notBuilt.forEach {
            logger.warn(
                "Skipping ${it.name}: its jar ${it.file} was not built, for example because its jar task is disabled"
            )
        }
        logger.lifecycle("Found ${built.size} SkillsJar(s)")

        extractedSkillFolders(outputPath).forEach { deleteRecursively(it, logger) }
        Files.createDirectories(outputPath)

        val extractedPaths = mutableMapOf<String, String>()

        for (skillsJar in built) {
            extractSkillsJar(skillsJar.name, skillsJar.file, outputPath, extractedPaths)
        }

        logger.lifecycle("Successfully extracted SkillsJars")
    }

    private fun commandLineProperty(name: String): Provider<String> =
        providers.gradleProperty(name).filter { it.isNotBlank() }

    private class SkillsJar(val name: String, val file: File, val fromProject: Boolean)

    private fun findSkillsJars(): List<SkillsJar> {
        val artifacts = skillArtifacts.orNull?.artifacts.orEmpty()
        val components = artifacts.map { it.id.componentIdentifier }.filter { it.isModuleOrProject() }
        // Names and skipped projects come from the graph only when it is the one the artifacts were resolved from
        val graph = skillRootComponent.orNull?.let(::SkillGraph)
            ?.takeIf { graph -> components.all { it in graph.names } }
        graph?.let(::reportSkippedProjects)

        val skillsJars = mutableListOf<SkillsJar>()
        artifacts.groupBy { it.id.componentIdentifier }.forEach { (component, componentArtifacts) ->
            if (!component.isModuleOrProject()) {
                componentArtifacts.forEach(::warnNotExtracted)
                return@forEach
            }
            val name = graph?.names?.get(component) ?: component.displayName
            componentArtifacts.forEach { artifact ->
                // A component with several jars, such as one with a classifier, names each by its file
                val jarName = if (componentArtifacts.size == 1) name else "$name (${artifact.file.name})"
                skillsJars += SkillsJar(jarName, artifact.file, component is ProjectComponentIdentifier)
            }
        }

        return skillsJars
    }

    private fun warnNotExtracted(artifact: ResolvedArtifactResult) {
        logger.warn(
            "Skipping ${artifact.id.displayName}: only module and project dependencies of the " +
                "$SKILL_CONFIGURATION_NAME configuration are extracted"
        )
    }

    private fun reportSkippedProjects(graph: SkillGraph) {
        val skipped = graph.skippedProjects
        if (skipped.isEmpty()) return

        // States the rule rather than asking to declare them, since they may be code libraries: which of them hold
        // skills is not known without building them
        val count = if (skipped.size == 1) {
            "1 transitive project dependency"
        } else {
            "${skipped.size} transitive project dependencies"
        }
        val summary = "Skipped $count: only projects declared with skill(project(...)) are extracted."
        logger.lifecycle(if (logger.isInfoEnabled) summary else "$summary Run with --info to see which.")
        if (logger.isInfoEnabled) {
            skipped.forEach { component ->
                logger.info(
                    "Skipping ${component.displayName}: transitive project dependencies are not extracted; " +
                        "declare it directly with skill(project(...)) to extract its skills"
                )
            }
        }
    }

    private fun extractSkillsJar(
        artifactName: String,
        jarFile: File,
        outputPath: Path,
        extractedPaths: MutableMap<String, String>,
    ) {
        logger.lifecycle("Extracting: $artifactName")

        // First pass: find SKILL.md files to identify skill roots
        val skillRoots = mutableMapOf<String, String>()
        JarFile(jarFile).use { jar ->
            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val entryName = entry.name

                val relativePath = stripSkillsPrefix(entryName) ?: continue
                if (entryName.endsWith("/SKILL.md")) {
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

                if (entry.isDirectory) continue
                val relativePath = stripSkillsPrefix(entryName) ?: continue

                // Find the skill root for this file
                val rootEntry = skillRoots.entries.firstOrNull { relativePath.startsWith(it.key) }
                if (rootEntry == null) {
                    logger.warn("Skipping file not under a SKILL.md root: $relativePath")
                    continue
                }

                val (skillRoot, flattenedRoot) = rootEntry
                val remainder = relativePath.substring(skillRoot.length)
                val targetPath = outputPath.resolve("$EXTRACTED_SKILL_PREFIX$flattenedRoot").resolve(remainder)

                val conflictKey = "$EXTRACTED_SKILL_PREFIX$flattenedRoot/$remainder"
                val existing = extractedPaths[conflictKey]
                if (existing != null) {
                    throw GradleException(
                        "Path conflict detected: $conflictKey exists in both $existing and $artifactName"
                    )
                }

                extractedPaths[conflictKey] = artifactName

                Files.createDirectories(targetPath.parent)
                jar.getInputStream(entry).use { input ->
                    Files.copy(input, targetPath, StandardCopyOption.REPLACE_EXISTING)
                }

                logger.debug("Extracted: $conflictKey")
            }
        }
    }

    private fun stripSkillsPrefix(entryName: String): String? {
        for (prefix in SKILLS_PREFIXES) {
            if (entryName.startsWith(prefix)) {
                return entryName.substring(prefix.length)
            }
        }
        return null
    }
}
