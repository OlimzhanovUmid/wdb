rootProject.name = "windows-debug-bridge"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    // PREFER_PROJECT so wdb-plugin can declare the IntelliJ Platform repositories at project
    // level (the IntelliJ Platform Gradle Plugin requires them there); other modules declare
    // no repositories and fall back to the settings ones below.
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral()
        // Compose Desktop (dummy-app fixture) pulls androidx-compose + skiko artifacts from Google.
        google()
    }
}

include(
    "wdb-protocol",
    "wdb-client",
)
