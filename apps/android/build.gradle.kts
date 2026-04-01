plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

import java.util.Properties

val envFile = rootProject.file(".env")
val appEnv = Properties().apply {
    if (envFile.exists()) {
        envFile.inputStream().use(::load)
    }
}

fun envOrDefault(name: String, default: String = ""): String {
    return providers.environmentVariable(name).orNull
        ?: appEnv.getProperty(name)
        ?: default
}

fun String.asBuildConfigString(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.kibot.commandcenter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kibot.commandcenter"
        minSdk = 26
        targetSdk = 35
        versionCode = envOrDefault("KIBOT_ANDROID_VERSION_CODE", "1").toIntOrNull() ?: 1
        versionName = envOrDefault("KIBOT_ANDROID_VERSION_NAME", "1.0.0")

        buildConfigField("String", "DEFAULT_KIDAX_WS_URL", envOrDefault("KIDAX_WS_URL", "ws://213.35.118.26:8787/api/live/ws").asBuildConfigString())
        buildConfigField("String", "DEFAULT_KINANCE_WS_URL", envOrDefault("KINANCE_WS_URL", "ws://127.0.0.1:8788/api/live/ws").asBuildConfigString())
        buildConfigField("String", "DEFAULT_KIDAX_HTTP_URL", envOrDefault("SERVER_MONITOR_BASE_URL", "http://213.35.118.26:8787").asBuildConfigString())
        buildConfigField("String", "DEFAULT_KINANCE_HTTP_URL", envOrDefault("KINANCE_MONITOR_BASE_URL", "http://127.0.0.1:8788").asBuildConfigString())
        buildConfigField("String", "COMMAND_CENTER_TITLE", "\"KiBot\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":packages:shared-models"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.google.material)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(kotlin("test"))
}
