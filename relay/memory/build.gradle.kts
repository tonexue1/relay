plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "relay.memory"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
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
    api(project(":relay:agent-core"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.room3.runtime)
    implementation(libs.androidx.sqlite.bundled)
    ksp(libs.androidx.room3.compiler)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.sqlite.bundled.jvm)
}

configurations.configureEach {
    if (name.contains("UnitTest", ignoreCase = true)) {
        exclude(group = "androidx.sqlite", module = "sqlite-bundled-android")
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = System.getProperty("relay.liveEval").orEmpty().isNotBlank()
    }
    systemProperty("relay.liveEval", System.getProperty("relay.liveEval") ?: "")
}
