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
        maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
    }
}

rootProject.name = "MOCA"

include(":app")
include(":pleos-ds")
project(":pleos-ds").projectDir = file("../../pleos-connect-design-system/pleos-ds")

if (file("../core/build.gradle.kts").exists()) {
    include(":core")
    project(":core").projectDir = file("../core")
}
