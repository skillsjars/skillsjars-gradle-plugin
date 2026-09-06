package com.skillsjars.gradleplugin

import org.gradle.api.Action
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.dsl.DependencyHandler

/**
 * Adds a dependency to the `skill` configuration in Kotlin DSL builds.
 *
 * Example:
 * ```kotlin
 * dependencies {
 *     skill("com.skillsjars:my-skill:1.0.0")
 * }
 * ```
 */
fun DependencyHandler.skill(dependencyNotation: Any): Dependency? =
    add("skill", dependencyNotation)

/**
 * Adds a configurable external module dependency to the `skill` configuration in Kotlin DSL builds.
 *
 * Example:
 * ```kotlin
 * dependencies {
 *     skill("com.skillsjars:my-skill:1.0.0") {
 *         isTransitive = false
 *     }
 * }
 * ```
 */
fun DependencyHandler.skill(
    dependencyNotation: String,
    dependencyConfiguration: Action<in ExternalModuleDependency>
): ExternalModuleDependency {
    val dependency = create(dependencyNotation) as ExternalModuleDependency
    dependencyConfiguration.execute(dependency)
    add("skill", dependency)
    return dependency
}
