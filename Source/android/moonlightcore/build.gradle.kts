// Nucleo de streaming do Moonlight/Artemis (SEM a interface do Artemis): conexao, pareamento, decodificador de video (MediaCodec),
// audio e a biblioteca nativa moonlight-core. So os 29 arquivos Java necessarios + JNI (ver README_MOONLIGHT.md).
// Desligado por padrao: ative com  stormvr.moonlight=true  em gradle.properties.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.limelight"            // mesmo pacote do Artemis: os arquivos copiados nao precisam mudar
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        externalNativeBuild { ndkBuild { arguments("PRODUCT_FLAVOR=nonRoot") } }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        encoding = "UTF-8"
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    externalNativeBuild { ndkBuild { path = file("src/main/jni/Android.mk") } }
    packaging { resources { pickFirsts.add("META-INF/versions/9/OSGI-INF/MANIFEST.MF") } }
    lint { disable += "MissingTranslation" }
}

dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.81")
    implementation("org.jcodec:jcodec:0.2.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("com.google.ai.edge.litert:litert:1.4.0")
    implementation("com.google.ai.edge.litert:litert-gpu:1.4.0")
    implementation("org.opencv:opencv:4.12.0")
    implementation("androidx.preference:preference:1.2.1")     // ProfilesManager usa PreferenceManager
}
