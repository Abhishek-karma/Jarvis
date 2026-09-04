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

rootProject.name = "Jarvis"

include(":app")
include(":core:common")
include(":core:designsystem")
include(":core:database")
include(":core:network")
include(":core:agent")
include(":core:voice")
include(":core:navigation")
include(":feature:chat")
include(":feature:settings")
