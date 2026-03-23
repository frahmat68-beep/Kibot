plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "com.kibot.macengine.MainKt"
}

dependencies {
    implementation(project(":packages:shared-models"))
    implementation(project(":packages:core"))
    implementation(project(":packages:ai-support"))
    implementation(project(":packages:control-plane"))
    implementation(project(":packages:indodax-client"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.html.builder)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.slf4j.api)
    implementation(libs.jmdns)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testImplementation(project(":packages:test-kit"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Assemble a fat JAR containing the project and all runtime dependencies."

    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            "Implementation-Title" to "KiBot Mac Engine",
            "Implementation-Version" to project.version,
            "Main-Class" to "com.kibot.macengine.MainKt"
        )
    }

    from(sourceSets.main.get().output)

    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map {
            if (it.isDirectory) {
                it
            } else {
                zipTree(it)
            }
        }
    })

    // Prevent duplicate entries
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.register("fatJarCopy") {
    dependsOn("fatJar")
    doLast {
        copy {
            from(tasks.named("fatJar"))
            into(file("../"))
        }
    }
}
