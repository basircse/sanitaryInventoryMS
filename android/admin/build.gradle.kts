import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "com.basir.inventory.admin.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "Ajwa Inventory Admin"
            packageVersion = "1.0.0"
            vendor = "Basir"
            description = "License keys and backup viewer for Ajwa Sanitary inventory"
            windows {
                menu = true
                shortcut = true
                dirChooser = true
                upgradeUuid = "8d2f6a3e-4b7c-4e59-9a61-3f2b9d0c7e15"
            }
        }
    }
}

/**
 * One-time setup: creates the provider key pair in %APPDATA%\AjwaAdmin (if missing) and writes the
 * public halves to provider-keys/public.properties, which the Android build embeds.
 *   gradlew :admin:initProviderKeys
 */
tasks.register<JavaExec>("initProviderKeys") {
    group = "ajwa"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.basir.inventory.admin.InitKeysKt")
    args(rootProject.file("provider-keys/public.properties").absolutePath)
}

/** Converts a backup to a MySQL script: gradlew :admin:exportSql -Pbackup="C:\path\file.ajwb" */
tasks.register<JavaExec>("exportSql") {
    group = "ajwa"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.basir.inventory.admin.MysqlExportKt")
    args(providers.gradleProperty("backup").orElse("").get())
}
