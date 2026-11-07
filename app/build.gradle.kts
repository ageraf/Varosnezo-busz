plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "hu.abris.varosnezo"
    compileSdk = 35

    defaultConfig {
        applicationId = "hu.abris.varosnezo"
        minSdk = 29
        targetSdk = 35
        // CI futásszám -> mindig nagyobb verzió, így a telefon frissítésként telepíti
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = run
        versionName = "0.1.$run"
        // MOTIS útvonaltervező; saját szerverre váltáskor csak ezt kell átírni
        buildConfigField("String", "ROUTING_URL", "\"https://api.transitous.org\"")
    }

    // Fix aláíró kulcs (CI-ben secretből), hogy a frissítés törlés nélkül települjön
    val ks = rootProject.file("release.keystore")
    if (ks.exists()) {
        signingConfigs {
            create("fixed") {
                storeFile = ks
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "varosnezo"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
        buildTypes { getByName("debug") { signingConfig = signingConfigs.getByName("fixed") } }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
