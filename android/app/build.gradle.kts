plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val compose_version = "1.1.0-beta01"

android {
    compileSdk = 32

    sourceSets.all {
        kotlin.srcDir("src/$name/kotlin")
    }

    defaultConfig {
        applicationId = "edu.northsouth.cse323.ecgmonitor"
        minSdk = 26
        targetSdk = 32
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = compose_version
    }
    packagingOptions {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.7.0")
    implementation("androidx.compose.ui:ui:$compose_version")
    implementation("androidx.compose.material:material:$compose_version")
    implementation("androidx.compose.ui:ui-tooling-preview:$compose_version")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.3.1")
    implementation("androidx.activity:activity-compose:1.3.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.3")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.4.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:$compose_version")
    debugImplementation("androidx.compose.ui:ui-tooling:$compose_version")
    debugImplementation("androidx.compose.ui:ui-test-manifest:$compose_version")

    // SciChart libraries
    val scichart_version = "4.4.0.4739"
    implementation("com.scichart.library:core:$scichart_version@aar")
    implementation("com.scichart.library:data:$scichart_version@aar")
    implementation("com.scichart.library:drawing:$scichart_version@aar")
    implementation("com.scichart.library:charting3d:$scichart_version@aar")
    implementation("com.scichart.library:charting:$scichart_version@aar")
    implementation("com.scichart.library:extensions:$scichart_version@aar")
    implementation("com.scichart.library:extensions3d:$scichart_version@aar")
}