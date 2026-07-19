plugins {
    id("com.android.application")
}

android {
    namespace = "com.alvaro.ruidobranco"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.alvaro.ruidobranco"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
