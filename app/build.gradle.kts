plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Remote control (Firestore) is optional. The google-services plugin hard-fails
// the build when google-services.json is missing, which would break every
// tagged release build in CI for anyone who hasn't set up Firebase, so only
// apply it when the file is actually there. RemoteGate mirrors this at runtime.
val hasFirebaseConfig = file("google-services.json").exists()
if (hasFirebaseConfig) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.jasonschoenbrun.ytmtrigger"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jasonschoenbrun.ytmtrigger"
        minSdk = 34
        targetSdk = 35
        versionCode = 8
        versionName = "0.5.0"
        // Lets the app tell at runtime whether it was built with a Firebase
        // config, without probing for generated resources.
        buildConfigField("boolean", "HAS_FIREBASE", hasFirebaseConfig.toString())
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        // Needed so MainActivity can read BuildConfig.VERSION_NAME when
        // stamping exported self-test run records. AGP 8.x defaults to false.
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Remote control. Safe to compile in unconditionally — these are inert
    // until google-services.json exists and RemoteGate reports ready.
    //
    // Pinned to the 33.x line on purpose: firebase-auth 24.x (BOM 34.x) is
    // compiled with Kotlin 2.3 metadata, which this project's Kotlin 2.0.21
    // compiler refuses to read. Bumping the whole Kotlin/Compose toolchain
    // just to gain unused Firebase features is not worth the regression risk.
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-auth")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
