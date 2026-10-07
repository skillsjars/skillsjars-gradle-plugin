package com.skillsjars.gradleplugin

import org.gradle.api.GradleException
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.io.File
import java.nio.file.Path
import java.util.function.BiConsumer

/**
 * The directories the [ExtractSkillsJarsTask]s of a build tree extract to. It is registered on the root build, so the
 * tasks of all the builds of a composite build are checked against each other.
 *
 * Each task replaces the `skillsjars__` folders of its directory before extracting, so two tasks writing to the same
 * directory would leave only the SkillsJars of the one that ran last, for example when `-PoutputDir` is given to a
 * build in which several projects apply the plugin. The same happens when one directory is inside a `skillsjars__`
 * folder of the other. The second task to claim such a directory fails instead. Other directories inside one another
 * are not affected, since a task leaves everything but its `skillsjars__` folders in place.
 *
 * Projects that apply the plugin in their own `plugins {}` blocks, and the builds of a composite build, can each load
 * the plugin with a class loader of its own, so the service a task gets may not be an instance of the task's own
 * [ExtractionDirectories] class. The task therefore claims its directory through [BiConsumer], an interface every class
 * loader shares. Other versions of the plugin in the same build tree use the same service, so its name and [accept]
 * have to stay compatible.
 */
abstract class ExtractionDirectories : BuildService<BuildServiceParameters.None>, BiConsumer<File, String> {

    private val owners = mutableMapOf<Path, String>()

    /**
     * Claims [directory] for the task whose path in the build tree is [taskPath], failing when another task has
     * claimed the same directory, or one that is inside a `skillsjars__` folder of it or that has it inside one.
     */
    @Synchronized
    override fun accept(directory: File, taskPath: String) {
        // Canonical, so that two spellings of one directory, such as through a symbolic link, are the same claim
        val path = directory.canonicalFile.toPath()
        val overlapping = owners.entries.firstOrNull { (claimed, owner) ->
            owner != taskPath && (
                path == claimed ||
                    path.isInsideExtractedSkillFolderOf(claimed) ||
                    claimed.isInsideExtractedSkillFolderOf(path)
                )
        }
        if (overlapping != null) {
            val (claimed, owner) = overlapping
            val conflict = if (claimed == path) {
                "$owner and $taskPath both extract SkillsJars to $directory, and each replaces its SkillsJars " +
                    "first, so only the SkillsJars of one would remain."
            } else {
                "$owner extracts SkillsJars to $claimed and $taskPath to $path, one inside a skillsjars__ folder " +
                    "of the other, so the task extracting to the outer one would delete the SkillsJars of the other."
            }
            throw GradleException(
                "$conflict Give each project its own outputDir, or run the task of one project, for example " +
                    "./gradlew $taskPath"
            )
        }
        owners.putIfAbsent(path, taskPath)
    }
}
