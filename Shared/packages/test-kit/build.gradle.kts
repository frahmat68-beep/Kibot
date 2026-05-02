plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)

    sourceSets {
        main {
            kotlin.srcDir("src/jvmMain/kotlin")
        }
        test {
            kotlin.srcDir("src/jvmTest/kotlin")
        }
    }
}

dependencies {
    implementation(project(":packages:shared-models"))
    implementation(project(":packages:core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.truth)
}

tasks.test {
    useJUnitPlatform()
}
