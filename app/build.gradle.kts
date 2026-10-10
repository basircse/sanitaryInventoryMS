import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Provider public keys, produced by `gradlew :admin:initProviderKeys` (private halves never enter the app).
val providerKeys = Properties().apply {
    val f = rootProject.file("provider-keys/public.properties")
    check(f.exists()) { "Missing provider-keys/public.properties - run: gradlew :admin:initProviderKeys" }
    f.inputStream().use(::load)
}

// Optional release signing, read from keystore.properties (not committed).
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use(::load)
}

// Backup relay URL and token, read from relay.properties (not committed).
val relayProps = Properties().apply {
    val f = rootProject.file("relay.properties")
    if (f.exists()) f.inputStream().use(::load)
}

fun quoted(value: String?) = "\"" + (value ?: "") + "\""

android {
    namespace = "com.basir.inventory"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.basir.inventory"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.2"

        buildConfigField("String", "SIGN_PUBLIC", quoted(providerKeys.getProperty("signPublic")))
        buildConfigField("String", "BOX_PUBLIC", quoted(providerKeys.getProperty("boxPublic")))
        buildConfigField("String", "BACKUP_ENDPOINT", quoted(relayProps.getProperty("endpoint")))
        buildConfigField("String", "BACKUP_TOKEN", quoted(relayProps.getProperty("token")))
        buildConfigField("String", "SHOP_NAME", quoted("Ajwa Sanitary"))
        buildConfigField("String", "PROVIDER_PHONE", quoted("01818285008"))
        buildConfigField("String", "PROVIDER_WHATSAPP", quoted("8801818285008"))
    }

    signingConfigs {
        if (signingProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)

    implementation(libs.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    testImplementation(libs.junit)
}
