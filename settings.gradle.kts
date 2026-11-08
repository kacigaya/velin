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

rootProject.name = "velin"

include(":app")
include(":core:designsystem")
include(":feature:auth")
include(":feature:mail")

include(":core:domain")
include(":core:sdk")
include(":core:network")

include(":core:data")
