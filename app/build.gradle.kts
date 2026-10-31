plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.diligent.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.diligent.app"
        minSdk = 26
        targetSdk = 35
        // CI passes the number of commits on main, so every commit is a newer version
        // (.github/workflows/release.yml). Local builds stay 1 / 1.0.0.
        versionCode = (project.findProperty("ciVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("ciVersionName") as String?) ?: "1.0.0"

        // update.json of the newest GitHub release (see docs/UPDATES.md)
        buildConfigField(
            "String",
            "UPDATE_FEED_URL",
            "\"https://github.com/ArindamJaiman/Diligent/releases/latest/download/update.json\""
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Room schema export for migrations
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    // CI (.github/workflows/release.yml) decodes the keystore into app/release-keystore.jks
    // and passes the passwords as environment variables. Local builds without it stay unsigned.
    val releaseKeystore = file("release-keystore.jks")
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
            if (releaseKeystore.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            buildConfigField("boolean", "UPDATES_ENABLED", "true")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // A separate app (applicationId .debug), so release APKs can't update it.
            buildConfigField("boolean", "UPDATES_ENABLED", "false")
            isMinifyEnabled = false
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            // Duplicate metadata from the Claude SDK's Jackson / OkHttp dependencies
            excludes += setOf("META-INF/INDEX.LIST", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.version")
        }
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.ui.text.google.fonts)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.animation)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Glance (Widget)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Serialization (kotlinx only: generated serializers survive R8 obfuscation)
    implementation(libs.kotlinx.serialization.json)

    // Charts
    implementation(libs.vico.compose.m3)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Health Connect (exercise for Gym auto-log; sleep, steps and mindfulness for insights)
    implementation(libs.androidx.health.connect.client)

    // Geofences: "arrive at the gym → start the Gym timer"
    implementation(libs.play.services.location)

    // Wear OS tile: today's goals go to the watch, its + buttons come back
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)

    // Claude API for the weekly coach letter (Insights), called with the user's own API key
    implementation(libs.anthropic.java)

    // Optional vendor SDKs dropped into app/libs, e.g. Nothing's Glyph Matrix kit (docs/GLYPH.md).
    // The app reaches them through reflection, so it builds the same without them.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
