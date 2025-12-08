// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services") // For Firebase
    id("kotlin-kapt") // For Hilt annotation processing
    id("com.google.dagger.hilt.android") // For Hilt dependency injection
    id("kotlin-parcelize") // For Parcelable Channel model
    id("androidx.navigation.safeargs.kotlin") // For Navigation Safe Args
}

android {
    namespace = "com.livetvpro"
    compileSdk = 34 // Updated SDK version

    defaultConfig {
        applicationId = "com.livetvpro"
        minSdk = 24 // Minimum SDK for Media3
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false // Set to true for production
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Optional: Enable debug build specific settings
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true // Enable View Binding
    }
    packaging { // To avoid conflicts with ExoPlayer's resources
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.7")

    // Hilt (Dependency Injection)
    implementation("com.google.dagger:hilt-android:2.50")
    kapt("com.google.dagger:hilt-compiler:2.50") // Annotation processor for Hilt

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")

    // Media3 (ExoPlayer)
    implementation("androidx.media3:media3-exoplayer:1.3.1") // Updated version
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.3.1") // For HTTP(S) data source
    implementation("androidx.media3:media3-session:1.3.1") // If needed for media session
    implementation("androidx.media3:media3-common:1.3.1") // Common components

    // Glide (Image Loading)
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // Timber (Logging)
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Kotlin Coroutines (likely already included via lifecycle-ktx, but explicit)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

// Ensure Hilt plugin is applied correctly
kapt {
    correctErrorTypes = true
}
