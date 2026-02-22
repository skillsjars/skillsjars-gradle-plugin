package com.skillsjars.gradleplugin

import org.gradle.api.Plugin
import org.gradle.api.Project

class SkillsJarsGradlePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val taskProvider = project.tasks.register("extractSkillsJars", ExtractSkillsJarsTask::class.java)
        taskProvider.configure {
            group = "skillsjars"
            description = "Extract SkillsJars to a directory for AI agents"

            if (project.hasProperty("dir")) {
                dir.set(project.property("dir") as String)
            }
        }
    }
}
