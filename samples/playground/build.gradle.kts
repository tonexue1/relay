import java.util.Properties

plugins {
    // AGP 9 ships Kotlin support built in; applying kotlin-android on top is an error.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Convenience for local runs only: put `relay.deepseek.apiKey=sk-...` in the gitignored
 * `local.properties` to prefill the key field. Absent that, the key is typed in the app.
 */
val devApiKey: String = providers
    .fileContents(rootProject.layout.projectDirectory.file("local.properties"))
    .asText
    .map { text -> Properties().apply { load(text.reader()) }.getProperty("relay.deepseek.apiKey", "") }
    .getOrElse("")

android {
    namespace = "relay.demo"
    // AndroidX releases from 2026 refuse to be consumed below this.
    compileSdk = 37

    defaultConfig {
        applicationId = "relay.demo"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "DEEPSEEK_API_KEY", "\"$devApiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":relay:llm"))
    implementation(project(":relay:ondevice"))
    implementation(project(":relay:agent-core"))
    implementation(project(":relay:orchestra"))
    implementation(project(":relay:memory"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.sqlite.bundled.jvm)
}

configurations.configureEach {
    if (name.contains("UnitTest", ignoreCase = true)) {
        exclude(group = "androidx.sqlite", module = "sqlite-bundled-android")
    }
}
