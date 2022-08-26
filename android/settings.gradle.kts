pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://www.myget.org/F/abtsoftware/maven")  // for SciChart libraries
    }
}
rootProject.name = "ECG Monitor"
include(":app")
