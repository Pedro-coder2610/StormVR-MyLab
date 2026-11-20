plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.stormvr.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.stormvr.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.4.0"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { aidl = true }                                   // IShellService (UserService do Shizuku)
    packaging { resources { pickFirsts.add("META-INF/versions/9/OSGI-INF/MANIFEST.MF") } }   // bouncycastle duplicado (Moonlight)
}
dependencies {
    // S Pen Remote SDK da Samsung (opcional): coloque spenremote-v1.0.X.jar e sdk-v1.0.0.jar em app/libs. Sem eles o app compila igual (a S Pen so fica desligada).
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    // Vindas do app VR Room (agora dentro do StormVR): so a Sala VR usa estas duas.
    implementation("com.google.ar:core:1.44.0")                    // 6DoF (rastreamento de posicao por camera)
    implementation("com.google.mediapipe:tasks-vision:0.10.14")    // rastreamento de maos
    implementation(project(":moonlightcore"))                      // streaming Moonlight (nucleo do Artemis)
    // Shizuku (operacoes privilegiadas sem root no Android 11+): API oficial https://github.com/RikkaApps/Shizuku-API
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")             // ShizukuProvider: recebe o Binder do servico Shizuku ao abrir o app
}
