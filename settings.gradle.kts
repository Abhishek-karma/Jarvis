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
include(":core:ml")
include(":core:navigation")
include(":core:preferences")
include(":feature:chat")
include(":feature:settings")
