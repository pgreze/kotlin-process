@file:Suppress("UnstableApiUsage")

rootProject.name = "kotlin-process"

@Suppress("UnstableApiUsage")
dependencyResolutionManagement.repositories {
    mavenCentral()
}

plugins {
    id("de.fayard.refreshVersions") version "0.60.5"
}

refreshVersions {
    rejectVersionIf { candidate.stabilityLevel.isLessStableThan(current.stabilityLevel) }
}
