import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Convenience for local runs only: put keys in the gitignored `local.properties`.
 * Absent that, type them in the Clip UI.
 *
 *   relay.deepseek.apiKey=sk-...
 *   relay.bocha.apiKey=...
 */
val localPropertiesText = providers
    .fileContents(rootProject.layout.projectDirectory.file("local.properties"))
    .asText

fun localProperty(key: String): String = localPropertiesText
    .map { text -> Properties().apply { load(text.reader()) }.getProperty(key, "") }
    .getOrElse("")

val devApiKey: String = localProperty("relay.deepseek.apiKey")
val bochaApiKey: String = localProperty("relay.bocha.apiKey")

android {
    namespace = "relay.clip"
    compileSdk = 37

    defaultConfig {
        applicationId = "relay.demo.clip"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "DEEPSEEK_API_KEY", "\"$devApiKey\"")
        buildConfigField("String", "BOCHA_API_KEY", "\"$bochaApiKey\"")

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
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
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":relay:ondevice"))
    implementation(project(":relay:orchestra"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
    }
}
