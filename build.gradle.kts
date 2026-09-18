plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android { namespace = "com.nearcall.v2"; compileSdk = 35
    defaultConfig { applicationId = "com.nearcall.v2"; minSdk = 24; targetSdk = 35; versionCode = 2; versionName = "2.0" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
}
