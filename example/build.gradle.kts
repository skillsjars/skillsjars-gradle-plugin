plugins {
    java
    id("com.skillsjars.gradle-plugin") version "0.0.2"
}

group = "com.skillsjars.example"

dependencies {
    skill("com.skillsjars:anthropics__skills__pdf:2026_02_06-1ed29a0")
    skill("com.skillsjars:sivaprasadreddy__sivalabs-agent-skills__spring-boot:2026_02_23-dba3310")
}

