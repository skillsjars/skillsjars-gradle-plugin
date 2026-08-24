package com.skillsjars.gradleplugin

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
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
 * - Integration with `clean` to delete configured extraction output directories.
 */
class SkillsJarsGradlePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.configurations.maybeCreate("skill").apply {
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
            projectGroup.convention(project.provider { project.group.toString() })
        }

        project.plugins.withType(JavaPlugin::class.java) {
            val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
            val mainSourceSet = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)
            mainSourceSet.resources.srcDir(packageTaskProvider.flatMap { it.outputDir })

            project.tasks.named(JavaPlugin.PROCESS_RESOURCES_TASK_NAME) {
                dependsOn(packageTaskProvider)
            }
        }

        project.tasks.register("extractSkillsJars", ExtractSkillsJarsTask::class.java) {
            group = "skillsjars"
            description = "Extract SkillsJars to a directory for AI agents"
            outputDir.convention(extension.outputDir)
        }

        project.tasks.withType(Delete::class.java).configureEach {
            if (name == "clean") {
                delete(project.files(project.provider {
                    val dir = extension.outputDir.orNull
                    if (dir != null) listOf(dir) else emptyList<Any>()
                }))
            }
        }
    }
}
