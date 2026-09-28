import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Each build gets a larger version code (seconds since 2026), so the phone installs every new APK
// as an update of the previous one.
val buildTime: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC)
val buildNumber = (buildTime.toEpochSecond() - 1_767_225_600).toInt()

android {
    namespace = "com.ozvuchka.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ozvuchka.app"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.2 (" + buildTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + " UTC)"
        ndk { abiFilters += "arm64-v8a" }
    }

    // Android installs a new version over the old one, keeping books and settings, only when both
    // are signed with the same key. Without a key of its own each CI runner signs with a fresh debug
    // key, so the app's permanent key comes from the environment (repository secrets on CI).
    val appKey = System.getenv("OZVUCHKA_KEYSTORE")?.let(::file)?.takeIf { it.isFile }
    signingConfigs {
        if (appKey != null) {
            create("ozvuchka") {
                storeFile = appKey
                storePassword = System.getenv("OZVUCHKA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OZVUCHKA_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "ozvuchka"
                keyPassword = System.getenv("OZVUCHKA_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
                    ?: System.getenv("OZVUCHKA_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug { if (appKey != null) signingConfig = signingConfigs.getByName("ozvuchka") }
        release {
            isMinifyEnabled = false
            if (appKey != null) signingConfig = signingConfigs.getByName("ozvuchka")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jsoup:jsoup:1.21.2")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    testImplementation("junit:junit:4.13.2")
    // The real org.json, so storage code can be tested off the device.
    testImplementation("org.json:json:20240303")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
