pluginManagement {
    repositories {
        google()
        // Google-hosted mirror of Maven Central (same artifacts) — avoids Central rate limits on CI.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google-hosted mirror of Maven Central (same artifacts) — avoids Central rate limits on CI.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}
rootProject.name = "EloaLauncher"
include(":app")
include(":core:common")
include(":core:designsystem")
include(":core:data")
include(":domain:bedrock")
include(":domain:java")
