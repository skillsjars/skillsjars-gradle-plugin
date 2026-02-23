# SkillsJars Gradle Plugin

Gradle plugin to extract SkillsJars from dependencies to a directory for AI agents.

## Usage

1. Find Agent SkillsJars on [SkillsJars.com](https://skillsjars.com/)
1. Add SkillsJars dependencies to your project
1. Add the plugin:
    ```kotlin
    plugins {
        id("com.skillsjars.gradle-plugin") version "0.0.2"
    }
    ```
1. Extract SkillsJars to the directory your AI uses, like:
    ```bash
    ./gradlew extractSkillsJars -Pdir=.kiro/skills
    ```
