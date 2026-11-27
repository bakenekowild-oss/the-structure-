// The Structure — personal voice assistant (free, offline, on-device brain).
// Root Gradle build. Java 21, AGP 8.8.2, Kotlin 2.0.21, Gradle 8.11.1.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "hey-gobi-app"
include(":app")
