package com.skillsjars.gradleplugin

import org.gradle.api.logging.Logger
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Comparator

/**
 * The prefix of the folders [ExtractSkillsJarsTask] extracts skills to. Extracting replaces these folders, and `clean`
 * deletes them, leaving anything else in the output directory, such as skills a project keeps there itself.
 */
internal const val EXTRACTED_SKILL_PREFIX = "skillsjars__"

/**
 * The folders of extracted skills in [directory].
 */
internal fun extractedSkillFolders(directory: Path): List<Path> {
    if (!Files.isDirectory(directory)) return emptyList()

    return Files.list(directory).use { children ->
        children.filter { it.fileName.toString().startsWith(EXTRACTED_SKILL_PREFIX) }.toList()
    }
}

/**
 * Whether this path is inside an extracted skill folder of [directory], which extracting to [directory] replaces.
 */
internal fun Path.isInsideExtractedSkillFolderOf(directory: Path): Boolean =
    this != directory &&
        startsWith(directory) &&
        directory.relativize(this).getName(0).toString().startsWith(EXTRACTED_SKILL_PREFIX)

/**
 * Deletes [path] and everything under it, without following symbolic links, warning about what cannot be deleted.
 */
internal fun deleteRecursively(path: Path, logger: Logger) {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return

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

/**
 * Deletes the extracted skills in [directory], and the directory itself when nothing else is left in it.
 */
internal fun deleteExtractedSkills(directory: Path, logger: Logger) {
    extractedSkillFolders(directory).forEach { deleteRecursively(it, logger) }

    if (Files.isDirectory(directory) && Files.list(directory).use { it.findAny().isEmpty }) {
        Files.delete(directory)
    }
}
