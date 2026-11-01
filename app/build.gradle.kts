plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "tw.local.seaweather"
    compileSdk = 35
    defaultConfig {
        applicationId = "tw.local.seaweather"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// ApiKey.kt holds a personal key and is git-ignored, so a fresh clone starts from the empty template.
val apiKeyFile = file("src/main/java/tw/local/seaweather/ApiKey.kt")
if (!apiKeyFile.exists()) file("ApiKey.kt.example").copyTo(apiKeyFile)
