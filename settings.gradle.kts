pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("androidx[.].*")
                includeGroupByRegex("com[.]android([.].*)?")
                includeGroupByRegex("com[.]google[.]testing[.].*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("androidx[.].*")
                includeGroupByRegex("com[.]android([.].*)?")
                includeGroupByRegex("com[.]google[.]testing[.].*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "morsebook"
include("core", "cli", "app")
