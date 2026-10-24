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

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed", "skipped") }
    finalizedBy(tasks.jacocoTestReport)
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
