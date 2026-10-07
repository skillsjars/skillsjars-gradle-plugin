package com.skillsjars.gradleplugin

import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.Directory
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer

/**
 * Gradle plugin for packaging and extracting SkillsJars.
 *
 * This plugin sets up:
 * - A dedicated `skill` dependency configuration for declaring SkillsJar dependencies.
 * - The `skillsjars` extension for configuring output directories, source directories, allowed tools, and repository coordinates.
 * - The `packageSkillsJars` task for bundling local skills into `META-INF/skills/...` resources.
 * - The `extractSkillsJars` task for extracting skill content from dependencies into a designated directory for AI agents.
 * - Integration with `clean` to delete the skills extracted to the configured output directory.
 *
 * Tasks read the project only while they are configured, so they are compatible with the configuration cache.
 */
class SkillsJarsGradlePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val skillConfiguration = project.configurations.maybeCreate(ExtractSkillsJarsTask.SKILL_CONFIGURATION_NAME).apply {
            isCanBeResolved = true
            isCanBeConsumed = false
            description = "SkillsJars dependencies for extraction"
        }

        val extension = project.extensions.create("skillsjars", SkillsJarsExtension::class.java).apply {
            sourceDir.convention(project.layout.projectDirectory.dir("skills"))
            allowedTools.convention(emptyMap())
        }

        val packageTaskProvider = project.tasks.register("packageSkillsJars", PackageSkillsJarsTask::class.java) {
            group = "skillsjars"
            description = "Package local skills into managed resources under META-INF/skills"
            sourceDir.convention(extension.sourceDir)
            allowedTools.convention(extension.allowedTools)
            gitHubUrl.convention(extension.gitHubUrl)
            outputDir.convention(project.layout.buildDirectory.dir("generated/resources/skillsjars"))
        }

        project.plugins.withType(JavaPlugin::class.java) {
            val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
            val mainSourceSet = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)
            mainSourceSet.resources.srcDir(packageTaskProvider.flatMap { it.outputDir })

            project.tasks.named(JavaPlugin.PROCESS_RESOURCES_TASK_NAME) {
                dependsOn(packageTaskProvider)
            }
        }

        // A project dependency on `skill` is extracted from its own jar. Its project dependencies are left out, so
        // that extracting does not build modules that are only on its runtime classpath. Module dependencies are
        // extracted transitively, including those substituted with projects from an included build and the projects
        // they depend on in that build, as the published modules would be.
        val skillRootComponent = skillConfiguration.incoming.resolutionResult.rootComponent
        val skillGraph = lazy { SkillGraph(skillRootComponent.get()) }
        // File dependencies stay in the view, so that the task reports them as skipped
        val skillArtifacts = skillConfiguration.incoming.artifactView {
            componentFilter { !it.isModuleOrProject() || it in skillGraph.value.extracted }
        }.artifacts

        // Conventions, so that values a build gives a task registered before the plugin is applied are kept
        project.tasks.withType(ExtractSkillsJarsTask::class.java).configureEach {
            this.skillArtifacts.convention(skillArtifacts)
            this.skillRootComponent.convention(skillRootComponent)
        }

        project.tasks.register("extractSkillsJars", ExtractSkillsJarsTask::class.java) {
            group = "skillsjars"
            description = "Extract SkillsJars to a directory for AI agents"
            outputDir.convention(extension.outputDir)
        }

        val deleteExtractedSkills = DeleteExtractedSkills(extension.outputDir)
        project.tasks.withType(Delete::class.java).configureEach {
            if (name == "clean") {
                doLast(deleteExtractedSkills)
            }
        }
    }
}

/**
 * Deletes the skills extracted to [directory], leaving anything else in it, such as skills a project keeps there
 * itself. The directory is deleted too when nothing else is left in it.
 */
private class DeleteExtractedSkills(private val directory: Provider<Directory>) : Action<Task> {
    override fun execute(task: Task) {
        directory.orNull?.let { deleteExtractedSkills(it.asFile.toPath(), task.logger) }
    }
}

/**
 * The components of a resolved `skill` graph, read in one traversal: the coordinates that name them, the components
 * to extract, and the projects left out.
 *
 * Every external module, every project of another build, such as an included build, and the projects of this build
 * that are direct dependencies of the configuration or requested as module coordinates are extracted when they are
 * reached through the root or another extracted component. Other projects of this build are left out, together with
 * the components only they bring in, so that extracting does not build or extract what is only on a SkillsJar
 * project's runtime classpath.
 */
internal class SkillGraph(root: ResolvedComponentResult) {

    /** The coordinates of the components reached, which name their SkillsJars. */
    val names: Map<ComponentIdentifier, String>

    /** The components whose artifacts are extracted. */
    val extracted: Set<ComponentIdentifier>

    /** The projects of this build that are left out because they are only transitive project dependencies. */
    val skippedProjects: Set<ComponentIdentifier>

    init {
        val rootBuildPath = (root.id as? ProjectComponentIdentifier)?.build?.buildPath
        val names = mutableMapOf<ComponentIdentifier, String>()
        val extracted = mutableSetOf<ComponentIdentifier>()
        val skipped = mutableSetOf<ComponentIdentifier>()
        val visited = mutableSetOf<ComponentIdentifier>()
        val pending = ArrayDeque(listOf(root))

        while (pending.isNotEmpty()) {
            val component = pending.removeFirst()
            if (!visited.add(component.id)) continue

            component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { dependency ->
                val selected = dependency.selected
                val id = selected.id
                names.putIfAbsent(id, selected.moduleVersion?.toString() ?: id.displayName)
                val isExtracted = id !is ProjectComponentIdentifier ||
                    id.build.buildPath != rootBuildPath ||
                    component.id == root.id ||
                    dependency.requested is ModuleComponentSelector
                if (isExtracted) {
                    extracted.add(id)
                    pending.add(selected)
                } else {
                    skipped.add(id)
                }
            }
        }

        this.names = names
        this.extracted = extracted
        this.skippedProjects = skipped - extracted
    }
}

/**
 * Whether this is a module or a project, whose artifacts are extracted, rather than, for example, a file dependency.
 */
internal fun ComponentIdentifier.isModuleOrProject() =
    this is ModuleComponentIdentifier || this is ProjectComponentIdentifier
