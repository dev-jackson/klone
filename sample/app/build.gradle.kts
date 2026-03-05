plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("dev.klone.project")
}

android {
    namespace = "com.example.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.sample"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions { jvmTarget = "11" }
}

dependencies {
    gitImplementation("https://github.com/square/retrofit", from = "2.9.0")
    gitImplementation("https://github.com/square/okhttp", from = "4.12.0")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
}
