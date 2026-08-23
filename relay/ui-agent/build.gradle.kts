plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "relay.uiagent"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":relay:agent-core"))
    api(project(":relay:artifacts"))
    api(project(":relay:ui-kit"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
