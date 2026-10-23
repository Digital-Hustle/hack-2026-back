pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }

rootProject.name = "cashflow-backend"

file("services").listFiles()?.forEach { serviceDir ->
    if (serviceDir.isDirectory && file("services/${serviceDir.name}/build.gradle.kts").exists()) {
        include(":services:${serviceDir.name}")
        project(":services:${serviceDir.name}").projectDir = file("services/${serviceDir.name}")
    }
}
