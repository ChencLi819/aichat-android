import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: reads a properties file kept OUTSIDE the repo
// (storeFile / storePassword / keyAlias / keyPassword). Override the path with
// the JEV_KEYSTORE_PROPS env var. Without it, release builds are unsigned.
val releaseProps = Properties().apply {
    val f = file(System.getenv("JEV_KEYSTORE_PROPS") ?: "H:/android/keys/jev-release.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

// Pipeline-test route, also kept OUT of the repo (git-ignored). Put
// `judgeKey=` / `judgeBaseUrl=` / `judgeModel=` in app/pipeline-test.properties
// and the live tests in PipelineTest drive that route. Without the file they
// fall back to whatever the app itself is configured with, and skip when that
// is empty too — so the suite is safe to run anywhere.
val pipelineProps = Properties().apply {
    val f = file("pipeline-test.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

android {
    namespace = "com.jev.probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jev.probe"
        minSdk = 30
        targetSdk = 35
        versionCode = 6
        versionName = "1.5"

        // On-device tests: the pipeline test drives the real judge/reply routes,
        // so it has to run where the app's network stack and prefs live.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        listOf("judgeKey", "judgeBaseUrl", "judgeModel").forEach { k ->
            pipelineProps.getProperty(k)?.let { testInstrumentationRunnerArguments[k] = it }
        }

        // ML Kit's bundled Chinese recognizer ships native libs for every ABI.
        // The target phone (and every phone this can run on: minSdk 30) is
        // arm64, so keep only that one — the other three are dead weight.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (releaseProps.isNotEmpty()) {
            create("release") {
                storeFile = file(releaseProps.getProperty("storeFile"))
                storePassword = releaseProps.getProperty("storePassword")
                keyAlias = releaseProps.getProperty("keyAlias")
                keyPassword = releaseProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // Uncompressed, page-aligned .so files: required for the 16 KB page-size
    // devices Android 15+ ships, and it lets the loader mmap the ML Kit natives
    // instead of unpacking them at install time.
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // On-device OCR. The *bundled* Chinese model (not the play-services variant):
    // it works on phones with no Google Play services and needs no model download.
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // Pipeline test (androidTest). Reads the API key from the app's own prefs —
    // a key never appears in source.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
