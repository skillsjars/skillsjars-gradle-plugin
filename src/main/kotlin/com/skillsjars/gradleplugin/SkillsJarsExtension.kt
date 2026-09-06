package com.skillsjars.gradleplugin

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property

/**
 * Extension for configuring the SkillsJars Gradle plugin.
 *
 * @property outputDir The target directory for extracting SkillsJars. Can be overridden via `-PoutputDir` (or `-Pdir`).
 * @property sourceDir The directory containing local skills to package (defaults to `skills/`).
 * @property allowedTools Map of skill names to expected `allowed-tools` frontmatter values for validation.
 * @property gitHubUrl Optional GitHub repository URL (e.g. `https://github.com/org/repo`) used to determine package path in the JAR.
 */
interface SkillsJarsExtension {
    val outputDir: DirectoryProperty
    val sourceDir: DirectoryProperty
    val allowedTools: MapProperty<String, String>
    val gitHubUrl: Property<String>
}
