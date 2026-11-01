plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.dmrmap"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dmrmap"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.osmdroid)
    testImplementation(libs.junit)
}
