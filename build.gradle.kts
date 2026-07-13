plugins {
    kotlin("jvm") version "2.4.0" apply false
    kotlin("plugin.serialization") version "2.4.0" apply false
    id("com.gradleup.shadow") version "8.3.6" apply false
}

group = "com.example.mcp"
version = "1.0-SNAPSHOT"

subprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
    }
}
