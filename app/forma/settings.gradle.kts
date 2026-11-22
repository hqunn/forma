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

rootProject.name = "FORMA"

// :assessment is a plain Kotlin/JVM module and deliberately has no Android
// dependency. Chapter 6.4 requires it; Chapter 11 tier T1 depends on it,
// because a module that cannot see Android can be tested without a device.
include(":assessment")

// :app is the Android half: camera, inference, audio and the interface. It
// depends on :assessment and :assessment never depends on it. That direction
// is the whole of Chapter 6.4's module argument, and reversing it would take
// the test suite off the build machine and onto a handset.
include(":app")
