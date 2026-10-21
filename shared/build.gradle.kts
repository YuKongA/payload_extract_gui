plugins {
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    id("payload.build-logic")
}

val generatedSrcDir = layout.buildDirectory.dir("generated/projectConfig")

kotlin {
    android {
        androidResources.enable = true
        compileSdk {
            version = release(ProjectConfig.Android.COMPILE_SDK) {
                minorApiLevel = ProjectConfig.Android.COMPILE_SDK_MINOR
            }
        }
        minSdk = ProjectConfig.Android.MIN_SDK
        namespace = "${ProjectConfig.PACKAGE_NAME}.shared"
    }

    jvm("desktop")

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(generatedSrcDir.map { it.dir("kotlin") })
        }
        commonMain.dependencies {
            api(libs.miuix.ui)
            implementation(libs.miuix.preference)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.components.resources)
        }

        named("androidMain").dependencies {
            implementation(libs.androidx.activity)
        }

        named("desktopMain").dependencies {
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}

compose.resources {
    publicResClass = true
}

val generateVersionInfo by tasks.registering(GenerateVersionInfoTask::class) {
    description = "GenerateVersionInfoTask"
    versionName.set(ProjectConfig.VERSION_NAME)
    versionCode.set(getGitVersionCode())
    outputFile.set(generatedSrcDir.map { it.file("kotlin/misc/VersionInfo.kt") })
}

tasks.named("generateComposeResClass").configure {
    dependsOn(generateVersionInfo)
}
