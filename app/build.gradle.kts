import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// ─────────────────────────────────────────────────────────────
// Load local.properties (MUST be above the android { } block)
// ─────────────────────────────────────────────────────────────
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

val rapidApiKey: String =
    localProps.getProperty("RAPIDAPI_KEY") ?: ""

val rapidApiHost: String =
    localProps.getProperty("RAPIDAPI_HOST")
        ?: "instagram-downloader-download-instagram-videos-stories1.p.rapidapi.com"

if (rapidApiKey.isEmpty()) {
    logger.warn("⚠️  RAPIDAPI_KEY is missing from local.properties — API calls will fail.")
}

android {
    namespace = "com.app.aio"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.app.aio"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ── Expose secrets to Java/Kotlin code ──
        buildConfigField("String", "RAPIDAPI_KEY",  "\"$rapidApiKey\"")
        buildConfigField("String", "RAPIDAPI_HOST", "\"$rapidApiHost\"")
    }

    buildFeatures {
        buildConfig = true   // required on AGP 8+ to generate BuildConfig
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)

    implementation("com.squareup.okhttp3:okhttp:5.5.1")
}