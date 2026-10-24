plugins {
    java
    jacoco
    checkstyle
    alias(libs.plugins.spring.boot)
}

group = "ru.cashflow"
version = "0.1.0"

repositories { mavenCentral() }

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.web)
    implementation(libs.spring.boot.validation)
    implementation(libs.spring.boot.jdbc)
    implementation(libs.liquibase)
    runtimeOnly(libs.postgresql)
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.embedded.postgres)
    testImplementation(libs.archunit)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

val forecastProject = rootProject.findProject(":services:forecast")
val standaloneForecastBuild = if (forecastProject == null) {
    tasks.register<Exec>("buildForecastForContractTest") {
        workingDir("../forecast")
        if (System.getProperty("os.name").startsWith("Windows")) {
            commandLine("cmd", "/c", "gradlew.bat", "bootJar", "--console=plain")
        } else {
            commandLine("bash", "gradlew", "bootJar", "--console=plain")
        }
    }
} else null

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed", "skipped") }
    finalizedBy(tasks.jacocoTestReport)
    if (forecastProject != null) {
        dependsOn(":services:forecast:bootJar")
        systemProperty("forecast.jar", forecastProject.layout.buildDirectory.file("libs/forecast.jar").get().asFile.absolutePath)
        inputs.file(forecastProject.layout.buildDirectory.file("libs/forecast.jar"))
    } else {
        dependsOn(requireNotNull(standaloneForecastBuild))
        systemProperty("forecast.jar", file("../forecast/build/libs/forecast.jar").absolutePath)
        inputs.file(file("../forecast/build/libs/forecast.jar"))
    }
}

jacoco { toolVersion = "0.8.13" }
tasks.jacocoTestReport { dependsOn(tasks.test); reports { xml.required = true } }

checkstyle {
    toolVersion = "10.21.4"
    configFile = file("config/checkstyle/checkstyle.xml")
    maxWarnings = 0
}

tasks.jar { enabled = false }
tasks.bootJar { archiveFileName = "orchestrator.jar" }
