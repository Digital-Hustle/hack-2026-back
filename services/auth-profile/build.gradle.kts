plugins {
    `java-library`
    alias(libs.plugins.spring.boot)
}

group = "ru.cashflow"
version = "0.1.0"

repositories { mavenCentral() }

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
    withSourcesJar()
}

val jooqCodegen by configurations.creating
val generatedJooq = layout.buildDirectory.dir("generated/sources/jooq")
sourceSets.main { java.srcDir(generatedJooq) }

dependencies {
    api(platform(libs.spring.boot.bom))
    api(libs.spring.webmvc)
    api(libs.spring.security.core)
    api(libs.jakarta.validation)
    api(libs.jackson.annotations)
    api(libs.jackson.databind)
    api(libs.jackson.jsr310)
    implementation(libs.spring.boot.web)
    implementation(libs.spring.boot.security)
    implementation(libs.spring.boot.validation)
    implementation(libs.spring.boot.jooq)
    implementation(libs.jooq)
    implementation(libs.spring.boot.mail)
    implementation(libs.spring.boot.actuator)
    implementation(libs.spring.security.jose)
    implementation(libs.liquibase)
    implementation(libs.mapstruct)
    compileOnly(libs.lombok)
    annotationProcessor(platform(libs.spring.boot.bom))
    annotationProcessor(libs.lombok)
    annotationProcessor(libs.lombok.mapstruct.binding)
    annotationProcessor(libs.mapstruct.processor)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(platform(libs.spring.boot.bom))
    testAnnotationProcessor(libs.lombok)
    runtimeOnly(libs.postgresql)
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.boot.validation)
    testImplementation(libs.jakarta.servlet)
    testImplementation(libs.jackson.yaml)
    testImplementation(libs.swagger.parser)
    testImplementation(libs.json.schema.validator)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.embedded.postgres)
    testImplementation(libs.archunit)
    jooqCodegen(platform(libs.spring.boot.bom))
    jooqCodegen(libs.jooq.codegen)
    jooqCodegen(libs.liquibase)
    jooqCodegen(libs.postgresql)
    jooqCodegen(libs.embedded.postgres)
}

configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jooq") useVersion(libs.versions.jooq.get())
    }
}

val compileJooqTool by tasks.registering(JavaCompile::class) {
    source("src/codegen/java")
    classpath = jooqCodegen
    destinationDirectory = layout.buildDirectory.dir("classes/codegen")
    javaCompiler = javaToolchains.compilerFor { languageVersion = JavaLanguageVersion.of(21) }
}
val generateJooq by tasks.registering(JavaExec::class) {
    group = "code generation"
    description = "Applies Liquibase to a temporary PostgreSQL and generates typed jOOQ tables and records."
    dependsOn(compileJooqTool)
    classpath = files(compileJooqTool.flatMap { it.destinationDirectory }) + jooqCodegen
    mainClass = "ru.cashflow.codegen.GenerateJooq"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    args(file("src/main/resources").absolutePath, generatedJooq.get().asFile.absolutePath)
    inputs.dir("src/main/resources/db/changelog")
    inputs.files(fileTree("src/codegen/java"))
    outputs.dir(generatedJooq)
}
tasks.compileJava { dependsOn(generateJooq) }
tasks.named("sourcesJar") { dependsOn(generateJooq) }

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.processResources {
    from(rootProject.file("docs/openapi/auth-profile.yaml")) { into("openapi") }
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed", "skipped") }
}
