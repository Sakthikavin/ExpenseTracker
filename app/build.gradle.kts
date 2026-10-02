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

    // The build *type* must not be what selects the Firestore backend: a debug build is what gets
    // installed for testing, and tying the emulator to it would point every such install at a
    // local emulator that isn't running. Local emulator testing opts in instead via
    // `-PfirestoreEmulator=true` (or `firestoreEmulator=true` in a gitignored gradle.properties),
    // which nothing sets by default.
    val useEmulatorRules = (findProperty("firestoreEmulator") as String?)?.toBoolean() ?: false

    defaultConfig {
        applicationId = "com.example.expensetracker"
        minSdk = 24
        targetSdk = 37
        // Bump both on every release, in step with the git tag (`v1.2.0` → `versionName = "1.2.0"`,
        // `versionCode = 3`). Tags stay three-segment, because the release workflow's manual
        // "Run workflow" path bumps the last segment of the latest tag — from a two-segment tag it
        // would step the minor version every time.
        //
        // `versionName` is the only thing a submission reports to the rules
        // console (`SubmissionRepository`), and it's how the console tells which redactor built a
        // template — left at "1.0" forever, it has to keep compensating for templates from builds
        // that no longer exist. `versionCode` is what the platform compares: raising it is what
        // makes a later APK an update, and what lets it refuse an *older* APK over a newer
        // database, which Room can only fail on.
        versionCode = 3
        versionName = "1.2.0"

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

    // Release signing comes from the environment (CI secrets, or a local export) rather than a
    // checked-in keystore. Without it the release variant stays unsigned and only `assembleRelease`
    // is affected — debug builds and the test tasks don't care.
    // providers.environmentVariable, never System.getenv: the latter reads the *daemon's*
    // environment, and CI reuses a daemon started by an earlier step that had none of these set —
    // the signing config then silently vanished and AGP fell back to a generated debug keystore.
    fun env(name: String) = providers.environmentVariable(name).orNull

    val keystorePath = env("RELEASE_KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = env("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = env("RELEASE_KEY_ALIAS")
                keyPassword = env("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.findByName("release")
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
    // instrumentation APK as assets. `src/test/resources` rides along for the same reason: it holds
    // the copies of the console's published rules, and both test source sets need them — one copy,
    // read from the classpath by unit tests and from assets by instrumented ones.
    sourceSets.getByName("androidTest") {
        assets.srcDirs(files("$projectDir/schemas"), files("$projectDir/src/test/resources"))
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
    // Real org.json on the unit-test classpath: android.jar's org.json is a throwing stub outside
    // instrumented tests, and RemoteRulesRepository/RemoteRulesApi parse real JSON in plain JVM tests.
    testImplementation(libs.org.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}