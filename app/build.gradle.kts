plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.expensetracker"
    compileSdk {
        version = release(37)
    }

    // release.yml ships the **debug** APK as-is (see INSTALL_ON_PHONE.md) — there is no separate
    // signed release build friends/family install. So the debug/release build *type* must not be
    // what selects the Firestore backend; that would point every real install at the local
    // emulator. Local emulator testing opts in instead via `-PfirestoreEmulator=true` (or
    // `firestoreEmulator=true` in a gitignored gradle.properties), which nothing sets by default.
    val useEmulatorRules = (findProperty("firestoreEmulator") as String?)?.toBoolean() ?: false

    defaultConfig {
        applicationId = "com.example.expensetracker"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        if (useEmulatorRules) {
            // Reached via `adb reverse tcp:8080 tcp:8080`, which tunnels the device's own
            // 127.0.0.1:8080 to the host's — works for AVDs and physical devices alike, unlike
            // the 10.0.2.2 NAT alias (not routed by every AVD's network config).
            buildConfigField("String", "FIRESTORE_PROJECT_ID", "\"demo-rules-console\"")
            buildConfigField("String", "FIRESTORE_BASE_URL", "\"http://127.0.0.1:8080\"")
        } else {
            buildConfigField("String", "FIRESTORE_PROJECT_ID", "\"expense-tracker-rules-console\"")
            buildConfigField("String", "FIRESTORE_BASE_URL", "\"https://firestore.googleapis.com\"")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    // MigrationTestHelper reads the exported schemas at runtime, so they have to ship inside the
    // instrumentation APK as assets.
    sourceSets.getByName("androidTest") {
        assets.srcDirs(files("$projectDir/schemas"))
    }
}

// AGP names the output APK after the module directory ("app") by default, giving app-debug.apk /
// app-release.apk. Rename it after the app itself instead; release drops the suffix since it's the
// one that ships.
androidComponents {
    onVariants(selector().all()) { variant ->
        val suffix = if (variant.buildType == "release") "" else "-${variant.buildType}"
        variant.outputs.forEach { output ->
            output.outputFileName.set("expense-tracker$suffix.apk")
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.datetime)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}