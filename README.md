# SkillsJars Gradle Plugin

Gradle plugin to extract SkillsJars published to Maven-compatible repositories into a local directory for downstream tooling, and to package local skills into your project's jar resources.

The plugin:

- extracts module dependencies transitively (any group ID), including the projects of included builds that provide them, and project dependencies declared directly in the `skill` configuration
- skips transitive project dependencies, and the modules only they bring in, saying how many it skipped; and skips file dependencies, and project dependencies whose jar was not built, with a warning
- looks for skill content in `META-INF/skills/` and `META-INF/resources/skills/`
- flattens each discovered skill root into `skillsjars__...`
- replaces only the `skillsjars__...` folders in the destination directory, preserving all other files and folders
- fails when a `skill` dependency does not resolve, or a module SkillsJar file is missing, leaving the destination directory as it was
- fails when the extraction tasks of two projects would write to the same destination directory
- fails on extracted path collisions
- packages local `skills/` directories into `META-INF/skills/...`
- validates `allowed-tools` frontmatter declarations during packaging
- supports the Gradle configuration cache

## Usage

The plugin requires Gradle 8.5 or later.

### 1. Apply the Plugin

**Kotlin DSL (`build.gradle.kts`)**
```kotlin
plugins {
    id("com.skillsjars.gradle-plugin") version "<version>"
}
```

**Groovy DSL (`build.gradle`)**
```groovy
plugins {
    id 'com.skillsjars.gradle-plugin' version '<version>'
}
```

### 2. Extracting Skills

Declare skill dependencies in the `skill` configuration:

**Kotlin DSL (`build.gradle.kts`)**
```kotlin
dependencies {
    skill("com.skillsjars:anthropics__skills__pdf:2026_02_06-1ed29a0")
    skill("org.example:custom-skill:1.0.0")
}
```

**Groovy DSL (`build.gradle`)**
```groovy
dependencies {
    skill 'com.skillsjars:anthropics__skills__pdf:2026_02_06-1ed29a0'
    skill 'org.example:custom-skill:1.0.0'
}
```

Declaring dependencies in the `skill` configuration keeps SkillsJars off your compile and runtime classpath — they are resolved only for extraction.

Module dependencies are extracted transitively. When an included build provides a module, its project and the projects it depends on in that build are extracted, as the published modules would be. A dependency declared with `skill(project(":skills-a"))` is extracted from its own jar. If `:skills-a` depends on another project of the same build, such as `implementation(project(":skills-b"))` or `runtimeOnly(project(":skills-b"))`, declare `skill(project(":skills-b"))` in the consuming project too to extract its skills. The modules that only `:skills-b` brings in are skipped with it. The task says how many transitive project dependencies it skipped; run with `--info` to see which. This avoids building projects that are only on a SkillsJar project's runtime classpath; projects needed to compile its jar are still built.

Configure a default output directory via the `skillsjars` extension:

**Kotlin DSL (`build.gradle.kts`)**
```kotlin
skillsjars {
    outputDir.set(layout.projectDirectory.dir(".agents/skills"))
}
```

**Groovy DSL (`build.gradle`)**
```groovy
skillsjars {
    outputDir = layout.projectDirectory.dir('.agents/skills')
}
```

Run the extraction task:

```bash
./gradlew extractSkillsJars
```

You can also pass `-PoutputDir` (or `-Pdir`) on the command line, which takes precedence over the extension:

```bash
./gradlew extractSkillsJars -PoutputDir=.agents/skills
```

Only Gradle properties are read for these options, such as those given with `-P` or set in the `gradle.properties` of the root project or the Gradle user home. A project property named `outputDir` or `dir`, including one set in a subproject's `gradle.properties`, does not change the output directory; set `outputDir` in that project's `skillsjars` extension instead. In a build where several projects apply the plugin, run the task of one project, for example `./gradlew :app:extractSkillsJars -PoutputDir=.agents/skills`: each extraction replaces the SkillsJars in its directory first, so the build fails when two of them would extract to the same one. This includes the tasks of the builds of a composite build.

Extraction replaces only the `skillsjars__...` folders in the output directory, preserving all other files and folders, including locally maintained skills. `clean` removes only those prefixed folders and deletes the output directory itself only if it is empty.

### 3. Packaging Skills

Place your skill directories under `skills/` (e.g. `skills/my-skill/SKILL.md`).

Configure GitHub URL (optional, defaults to project group as path) and any required `allowedTools`:

**Kotlin DSL (`build.gradle.kts`)**
```kotlin
skillsjars {
    gitHubUrl.set("https://github.com/my-org/my-repo")
    allowedTools.put("my-skill", "Bash Read Edit")
}
```

**Groovy DSL (`build.gradle`)**
```groovy
skillsjars {
    gitHubUrl = 'https://github.com/my-org/my-repo'
    allowedTools = ['my-skill': 'Bash Read Edit']
}
```

Run packaging:

```bash
./gradlew packageSkillsJars
```

When the `java` plugin is applied, `packageSkillsJars` is automatically wired into your `processResources` and `jar` tasks so skills are packaged directly into `META-INF/skills/...` in your project's JAR. A project without a `skills/` directory, such as one that only extracts skills, has nothing to package, and `packageSkillsJars` is skipped.
