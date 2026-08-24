# SkillsJars Gradle Plugin

Gradle plugin to extract SkillsJars published to Maven-compatible repositories into a local directory for downstream tooling, and to package local skills into your project's jar resources.

It mirrors the behavior of the SkillsJars sbt plugin:

- extracts all dependencies declared in the `skill` configuration (any group ID)
- looks for skill content in `META-INF/skills/` and `META-INF/resources/skills/`
- flattens each discovered skill root into `skillsjars__...`
- clears the destination directory before writing
- fails on extracted path collisions
- packages local `skills/` directories into `META-INF/skills/...`
- validates `allowed-tools` frontmatter declarations during packaging

## Usage

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

When the `java` plugin is applied, `packageSkillsJars` is automatically wired into your `processResources` and `jar` tasks so skills are packaged directly into `META-INF/skills/...` in your project's JAR.
