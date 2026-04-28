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
        // libsu is published via JitPack (com.github.topjohnwu.libsu:core).
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "burn-out"
include(":app")
