plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

import java.util.Properties

val keystoreEnvFile = rootProject.file(".secrets/android-keystore.env")
val keystoreEnv = Properties().apply {
    if (keystoreEnvFile.exists()) {
        keystoreEnvFile.inputStream().use(::load)
    }
}
val envFile = rootProject.file(".env")
val appEnv = Properties().apply {
    if (envFile.exists()) {
        envFile.inputStream().use(::load)
    }
}
val roomSchemaDir = providers.environmentVariable("KIBOT_ROOM_SCHEMA_DIR")
    .orElse("${System.getProperty("user.home")}/.kibot-room-schemas/android")
    .get()
val androidBuildDir = providers.environmentVariable("KIBOT_ANDROID_BUILD_DIR")
    .orElse("${System.getProperty("user.home")}/.kibot-build/apps-android")
    .get()

file(roomSchemaDir).mkdirs()
layout.buildDirectory.set(file(androidBuildDir))

fun envOrDefault(name: String, default: String = ""): String {
    return providers.environmentVariable(name).orNull
        ?: appEnv.getProperty(name)
        ?: default
}

fun String.asBuildConfigString(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.kibot.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kibot.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "KIBOT_BOT_ID", envOrDefault("BOT_ID", "main").asBuildConfigString())
        buildConfigField("String", "KIBOT_SUPABASE_URL", envOrDefault("SUPABASE_URL").asBuildConfigString())
        buildConfigField("String", "KIBOT_SUPABASE_ANON_KEY", envOrDefault("SUPABASE_ANON_KEY").asBuildConfigString())
        buildConfigField("String", "KIBOT_SUPABASE_USER_EMAIL", envOrDefault("SUPABASE_USER_EMAIL").asBuildConfigString())
        buildConfigField("String", "KIBOT_SUPABASE_USER_PASSWORD", envOrDefault("SUPABASE_USER_PASSWORD").asBuildConfigString())
        buildConfigField("String", "KIBOT_INDODAX_API_KEY", envOrDefault("INDODAX_API_KEY").asBuildConfigString())
        buildConfigField("String", "KIBOT_INDODAX_API_SECRET", envOrDefault("INDODAX_API_SECRET").asBuildConfigString())
        buildConfigField("String", "KIBOT_INDODAX_PUBLIC_BASE_URL", envOrDefault("INDODAX_PUBLIC_BASE_URL", "https://indodax.com/api").asBuildConfigString())
        buildConfigField("String", "KIBOT_INDODAX_PRIVATE_BASE_URL", envOrDefault("INDODAX_PRIVATE_BASE_URL", "https://indodax.com/tapi").asBuildConfigString())
        buildConfigField("String", "KIBOT_INDODAX_TRADE_API_V2_BASE_URL", envOrDefault("INDODAX_TRADE_API_V2_BASE_URL", "https://tapi.indodax.com").asBuildConfigString())
        buildConfigField("String", "KIBOT_INDODAX_WS_PUBLIC_URL", envOrDefault("INDODAX_WS_PUBLIC_URL", "wss://ws1.indodax.com/ws").asBuildConfigString())
        buildConfigField("String", "KIBOT_INDODAX_WS_PRIVATE_URL", envOrDefault("INDODAX_WS_PRIVATE_URL", "wss://pws.indodax.com/ws/?cf_ws_frame_ping_pong=true").asBuildConfigString())
        buildConfigField("boolean", "KIBOT_GEMINI_SUPPORT_ENABLED", envOrDefault("GEMINI_SUPPORT_ENABLED", "false"))
        buildConfigField("String", "KIBOT_GEMINI_SUPPORT_API_KEY", envOrDefault("GEMINI_SUPPORT_API_KEY").asBuildConfigString())
        buildConfigField("String", "KIBOT_GEMINI_SUPPORT_MODEL", envOrDefault("GEMINI_SUPPORT_MODEL", "gemini-2.0-flash-lite").asBuildConfigString())
        buildConfigField("int", "KIBOT_GEMINI_SUPPORT_MAX_CANDIDATES", envOrDefault("GEMINI_SUPPORT_MAX_CANDIDATES", "6"))
        buildConfigField("int", "KIBOT_GEMINI_SUPPORT_MIN_INTERVAL_MINUTES", envOrDefault("GEMINI_SUPPORT_MIN_INTERVAL_MINUTES", "240"))
        buildConfigField("long", "KIBOT_GEMINI_SUPPORT_TIMEOUT_MS", envOrDefault("GEMINI_SUPPORT_TIMEOUT_MS", "15000") + "L")
        buildConfigField("int", "KIBOT_GEMINI_SUPPORT_MAX_OUTPUT_TOKENS", envOrDefault("GEMINI_SUPPORT_MAX_OUTPUT_TOKENS", "384"))
        buildConfigField("int", "KIBOT_GEMINI_SUPPORT_HOURLY_REQUEST_BUDGET", envOrDefault("GEMINI_SUPPORT_HOURLY_REQUEST_BUDGET", "2"))
        buildConfigField("int", "KIBOT_GEMINI_SUPPORT_DAILY_REQUEST_BUDGET", envOrDefault("GEMINI_SUPPORT_DAILY_REQUEST_BUDGET", "12"))
        buildConfigField("int", "KIBOT_GEMINI_SUPPORT_FAILURE_COOLDOWN_MINUTES", envOrDefault("GEMINI_SUPPORT_FAILURE_COOLDOWN_MINUTES", "120"))
        buildConfigField("String", "KIBOT_MAC_LAN_SYNC_URL", envOrDefault("MAC_ENGINE_LAN_SYNC_URL").asBuildConfigString())
        buildConfigField("boolean", "KIBOT_ENABLE_LIVE_EXECUTION", envOrDefault("BOT_ENABLE_LIVE_EXECUTION", "false"))
        buildConfigField("long", "KIBOT_POLL_INTERVAL_MS", envOrDefault("BOT_POLL_INTERVAL_MS", "4000") + "L")
        buildConfigField("int", "KIBOT_LEASE_TTL_SECONDS", envOrDefault("BOT_DEFAULT_LEASE_TTL_SECONDS", "30"))
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        if (keystoreEnvFile.exists()) {
            create("privateRelease") {
                storeFile = file(keystoreEnv.getProperty("ANDROID_RELEASE_KEYSTORE_PATH"))
                storePassword = keystoreEnv.getProperty("ANDROID_RELEASE_STORE_PASSWORD")
                keyAlias = keystoreEnv.getProperty("ANDROID_RELEASE_KEY_ALIAS")
                keyPassword = keystoreEnv.getProperty("ANDROID_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreEnvFile.exists()) {
                signingConfig = signingConfigs.getByName("privateRelease")
            }
        }
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

room {
    // Room/KSP breaks when the project path contains spaces, so keep schemas in a stable user-local path.
    schemaDirectory(roomSchemaDir)
}

dependencies {
    implementation(project(":packages:shared-models"))
    implementation(project(":packages:core"))
    implementation(project(":packages:ai-support"))
    implementation(project(":packages:control-plane"))
    implementation(project(":packages:indodax-client"))

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
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.json)

    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(kotlin("test"))
}
