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
        versionCode = 3
        versionName = "1.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Assinatura instalável para teste da v1.2. A publicação na Play Store
            // usará uma chave de upload permanente, armazenada fora do repositório.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
}
