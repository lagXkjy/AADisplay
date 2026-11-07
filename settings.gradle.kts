pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

include(":aa-display", ":lib-stub", ":aare-aap")
project(":aare-aap").projectDir = file("vendor/aare-aap")
rootProject.name = "AADisplay"
