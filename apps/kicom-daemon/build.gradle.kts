plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    id("com.github.johnrengelman.shadow") version "8.1.1"
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "com.kibot.kicom.MainKt"
}

dependencies {
    implementation(project(":packages:shared-models"))
    implementation(project(":packages:core"))
    
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("all")
    mergeServiceFiles()
    manifest {
        attributes(
            "Implementation-Title" to "KiCom Global Scanner",
            "Implementation-Version" to project.version,
            "Main-Class" to "com.kibot.kicom.MainKt",
        )
    }
}

tasks.register("fatJar") {
    group = "build"
    description = "Assemble fat JAR via shadowJar for standalone deployment."
    dependsOn(tasks.named("shadowJar"))
}
