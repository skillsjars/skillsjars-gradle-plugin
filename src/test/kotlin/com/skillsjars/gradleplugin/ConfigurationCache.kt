package com.skillsjars.gradleplugin

import java.io.File

const val CONFIGURATION_CACHE_STORED = "Configuration cache entry stored"
const val CONFIGURATION_CACHE_REUSED = "Configuration cache entry reused"

/**
 * Runs every TestKit build of the test project in [projectDir] with the configuration cache,
 * failing the build on any configuration cache problem.
 */
fun enableConfigurationCache(projectDir: File) {
    File(projectDir, "gradle.properties").writeText(
        """
        org.gradle.configuration-cache=true
        org.gradle.configuration-cache.problems=fail
        """.trimIndent()
    )
}
