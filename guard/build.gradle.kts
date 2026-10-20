plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "net.ark3us.saferec.guard"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        // android.util.Log and friends return defaults instead of throwing in JVM tests
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.work.runtime)
    testImplementation(libs.junit)
}
