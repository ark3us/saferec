plugins {
    alias(libs.plugins.android.application)
}

android {
    packaging {
        resources {
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }

    namespace = "net.ark3us.saferec"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "net.ark3us.saferec"
        minSdk = 26
        targetSdk = 36
        versionCode = 16
        versionName = "1.15"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.swiperefreshlayout)

    implementation(libs.play.services.auth)
// Google API Client (Android) + Drive v3 generated library
    implementation(libs.google.api.client.android)
    implementation(libs.google.api.services.drive)

    implementation(libs.bcpkix.jdk15to18)


}