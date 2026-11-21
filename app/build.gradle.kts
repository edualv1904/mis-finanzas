import java.util.Properties

// La llave de Google Maps vive en local.properties (MAPS_API_KEY=...), fuera del código.
val mapsApiKey: String = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}.getProperty("MAPS_API_KEY", "")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.misfinanzas"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.misfinanzas"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
        buildConfigField("boolean", "HAY_MAPA", (mapsApiKey.isNotBlank()).toString())
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sin Play Store: firmamos el release con la llave de debug para poder instalarlo directo.
            signingConfig = signingConfigs.getByName("debug")
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.glance:glance-material3:1.1.1")
    implementation("com.google.maps.android:maps-compose:6.2.1")
    // Permiso de Google para subir el respaldo semanal a Drive (solo archivos que crea la app).
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    testImplementation("junit:junit:4.13.2")
}
