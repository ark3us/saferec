import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// The three power-key presses live in :guard. With the switch off the module is compileOnly:
// its manifest entries never reach the app, and BuildConfig.POWER_GESTURE is false
val powerGesture = providers.gradleProperty("saferec.powerGesture").map { it.toBoolean() }.getOrElse(true)

android {
    packaging {
        resources {
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }

    namespace = "net.ark3us.saferec"
    compileSdk = 36

    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localProperties.load(FileInputStream(localPropertiesFile))
    }

    signingConfigs {
        create("release") {
            storeFile = file("../keystore.jks")
            storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")
            keyAlias = "cert"
            keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")
        }
    }

    defaultConfig {
        applicationId = "net.ark3us.saferec"
        minSdk = 26
        targetSdk = 36
        versionCode = 16
        versionName = "1.15"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "POWER_GESTURE", powerGesture.toString())
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // The release build, shrunk the same way but signed with the debug key: it installs
        // over the debug app, keeping its data and Google consent, to test R8 on a phone
        create("r8test") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        // android.util.Log and friends return defaults instead of throwing in JVM tests
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        buildConfig = true
    }
}

// The evidence package ships the session verifier: copied from scripts/ at build time, so the
// app never carries a copy that drifted from the one in the repository
abstract class CopyVerifier : DefaultTask() {
    @get:InputFile
    abstract val script: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        script.get().asFile.copyTo(File(dir, "verify_session.py"), overwrite = true)
    }
}

val copyVerifier = tasks.register<CopyVerifier>("copyVerifier") {
    script.set(rootProject.file("scripts/verify_session.py"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyVerifier, CopyVerifier::outputDir)
    }
}

dependencies {
    testImplementation(libs.junit)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.swiperefreshlayout)
    implementation(libs.work.runtime)
    if (powerGesture) implementation(project(":guard")) else compileOnly(project(":guard"))

    implementation(libs.play.services.auth)
// Google API Client (Android) + Drive v3 generated library
    implementation(libs.google.api.client.android)
    implementation(libs.google.api.services.drive)

    implementation(libs.bcpkix.jdk15to18)


}