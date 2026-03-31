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

rootProject.name = "kibot"

include(
    ":apps:android",
    ":apps:mac-engine",
    ":packages:ai-support",
    ":packages:shared-models",
    ":packages:core",
    ":packages:control-plane",
    ":packages:indodax-client",
    ":packages:binance-client",
    ":packages:test-kit",
)
