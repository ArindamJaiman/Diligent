plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// The watch side of Diligent: one tile with today's goals and + buttons. Same applicationId
// and signing as the phone app, which the Wearable Data Layer requires to pair them.
android {
    namespace = "dev.diligent.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.diligent.app"
        minSdk = 30
        targetSdk = 35
        versionCode = (project.findProperty("ciVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("ciVersionName") as String?) ?: "1.0.0"
    }

    val releaseKeystore = rootProject.file("app/release-keystore.jks")
    signingConfigs {
        create("release") {
            if (releaseKeystore.exists()) {
                storeFile = releaseKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (releaseKeystore.exists()) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
        debug {
            // Matches the phone's debug app (dev.diligent.app.debug)
            applicationIdSuffix = ".debug"
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.wear.protolayout)
    implementation(libs.guava)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.serialization.json)
}
