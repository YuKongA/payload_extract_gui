import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm("desktop")

    sourceSets {
        named("desktopMain").dependencies {
            implementation(projects.shared)
            implementation(compose.desktop.currentOs)
            implementation(libs.components.resources)
        }
    }
}

// ============================================================
// Native library build tasks
// ============================================================

val nativeDir = rootProject.layout.projectDirectory.dir("native")
val nativeOutputDir = rootProject.layout.buildDirectory.dir("native")

val osName: String = System.getProperty("os.name").lowercase()
val nativeLibName: String = when {
    osName.contains("win") -> "payload_extract_jni.dll"
    osName.contains("mac") -> "libpayload_extract_jni.dylib"
    else -> "libpayload_extract_jni.so"
}

// build-std opt-in (RUST_BUILD_STD, set only in CI; unset locally -> plain stable build).
// Rebuilds std with optimize_for_size; needs the rust-src component.
val useBuildStd = !System.getenv("RUST_BUILD_STD").isNullOrBlank()

// build-std requires an explicit --target, which moves cargo's output directory.
val hostTriple: String? = if (useBuildStd) {
    providers.exec { commandLine("rustc", "-vV") }.standardOutput.asText.get()
        .lineSequence().first { it.startsWith("host: ") }.removePrefix("host: ").trim()
} else null
val cargoTargetDir = if (hostTriple != null) {
    nativeDir.dir("target/$hostTriple/release")
} else {
    nativeDir.dir("target/release")
}

val buildNativeLib by tasks.registering(Exec::class) {
    group = "native"
    description = "Build the Rust JNI native library${if (useBuildStd) " (build-std)" else ""}"
    workingDir = nativeDir.asFile
    val cmd = buildList {
        add("cargo"); add("build"); add("--release")
        if (hostTriple != null) {
            add("--target"); add(hostTriple)
            add("-Z"); add("build-std=std,panic_abort")
            add("-Z"); add("build-std-features=optimize_for_size")
        }
    }
    commandLine(*cmd.toTypedArray())
    if (useBuildStd) environment("RUSTC_BOOTSTRAP", "1")
    inputs.dir(nativeDir.dir("src"))
    inputs.file(nativeDir.file("Cargo.toml"))
    inputs.property("useBuildStd", useBuildStd)
    outputs.file(cargoTargetDir.file(nativeLibName))
}

// Copy native lib to a known location for development
val copyNativeLibForDev by tasks.registering(Copy::class) {
    group = "native"
    description = "Copy native library for development use"
    dependsOn(buildNativeLib)
    from(cargoTargetDir.file(nativeLibName))
    into(nativeOutputDir)
}

// For packaging: copy to appResources
val appResourcesDir = layout.projectDirectory.dir("resources")

val copyNativeLibForPackage by tasks.registering(Copy::class) {
    group = "native"
    description = "Copy native library to appResources for packaging"
    dependsOn(buildNativeLib)
    from(cargoTargetDir.file(nativeLibName))
    val targetDir = when {
        osName.contains("win") -> "windows-x64"
        osName.contains("mac") -> "macos-arm64"
        else -> "linux-x64"
    }
    into(appResourcesDir.dir(targetDir))
}

// Wire into build
tasks.named("desktopProcessResources") {
    dependsOn(copyNativeLibForDev)
}

afterEvaluate {
    tasks.named("prepareAppResources") {
        dependsOn(copyNativeLibForPackage)
    }
}

compose.desktop {
    application {
        mainClass = "MainKt"

        // Windows-only: FFM native access + reflective HWND (sun.awt). Gated on build host
        // so non-Windows packages don't warn about the missing sun.awt.windows package.
        if (System.getProperty("os.name").lowercase().contains("win")) {
            jvmArgs += listOf(
                "--enable-native-access=ALL-UNNAMED",
                "--add-opens", "java.desktop/sun.awt=ALL-UNNAMED",
                "--add-opens", "java.desktop/sun.awt.windows=ALL-UNNAMED",
            )
        }

        buildTypes.release.proguard {
            // Compose's bundled ProGuard maxes out at Java 24; pin a newer release that
            // understands JDK 25 class files (major version 69).
            version.set("7.9.1")
            configurationFiles.from("proguard-rules-jvm.pro")
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = ProjectConfig.APP_NAME
            packageVersion = ProjectConfig.VERSION_NAME
            appResourcesRootDir.set(appResourcesDir.asFile)

            windows.iconFile = project.file("src/desktopMain/resources/windows/Icon.ico")
            linux.iconFile = project.file("src/desktopMain/resources/linux/Icon.png")
            macOS.iconFile = project.file("src/desktopMain/resources/macos/Icon.icns")
        }
    }
}
