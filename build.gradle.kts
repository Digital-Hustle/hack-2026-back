plugins { base }

tasks.register("test")
subprojects {
    plugins.withId("java") {
        val service = this@subprojects
        rootProject.tasks.named("test") { dependsOn(service.tasks.named("test")) }
        rootProject.tasks.check { dependsOn(service.tasks.named("check")) }
        rootProject.tasks.assemble { dependsOn(service.tasks.named("assemble")) }
    }
}
