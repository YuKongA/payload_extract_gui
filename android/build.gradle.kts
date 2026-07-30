import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.shared)
    implementation(libs.androidx.activity)
}

android {
    val properties = Properties()
    runCatching { properties.load(project.rootProject.file("local.properties").inputStream()) }
    val keystorePath = properties.getProperty("KEYSTORE_PATH") ?: System.getenv("KEYSTORE_PATH")
    val keystorePwd = properties.getProperty("KEYSTORE_PASS") ?: System.getenv("KEYSTORE_PASS")
    val alias = properties.getProperty("KEY_ALIAS") ?: System.getenv("KEY_ALIAS")
    val pwd = properties.getProperty("KEY_PASSWORD") ?: System.getenv("KEY_PASSWORD")
    if (keystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = keystorePwd
                keyAlias = alias
                keyPassword = pwd
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileSdk {
        version = release(ProjectConfig.Android.COMPILE_SDK) {
            minorApiLevel = ProjectConfig.Android.COMPILE_SDK_MINOR
        }
    }
    defaultConfig {
        applicationId = ProjectConfig.PACKAGE_NAME
        minSdk = ProjectConfig.Android.MIN_SDK
        targetSdk = ProjectConfig.Android.TARGET_SDK
        versionName = ProjectConfig.VERSION_NAME
        versionCode = getGitVersionCode()
    }
    splits {
        abi {
            isEnable = true
            isUniversalApk = false
            reset()
            include("arm64-v8a", "x86_64")
        }
    }
    namespace = ProjectConfig.PACKAGE_NAME
}

// ============================================================
// Native library build tasks
// ============================================================

val nativeDir = rootProject.layout.projectDirectory.dir("native")

data class AndroidTarget(val rustTriple: String, val abi: String, val clangPrefix: String)

val androidTargets = listOf(
    AndroidTarget("aarch64-linux-android", "arm64-v8a", "aarch64-linux-android"),
    AndroidTarget("x86_64-linux-android", "x86_64", "x86_64-linux-android"),
)

val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")

val hostTag = when {
    System.getProperty("os.name").lowercase().contains("win") -> "windows-x86_64"
    System.getProperty("os.name").lowercase().contains("mac") -> "darwin-x86_64"
    else -> "linux-x86_64"
}

val clangSuffix = if (hostTag.startsWith("windows")) ".cmd" else ""

// ONDK opt-in (ONDK_HOME, set only in CI; unset locally -> SDK NDK). Adds -Z build-std +
// cross-language LTO. Setup: rustup toolchain link ondk <ONDK_HOME>/toolchains/rust
val ondkHome: String? = (System.getenv("ONDK_HOME")
    ?: project.findProperty("ondk.dir") as String?)?.takeIf { it.isNotBlank() }
val useOndk = ondkHome != null

val sdkDir: String by lazy {
    val props = Properties()
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localPropsFile.reader().use { reader -> props.load(reader) }
    }
    props.getProperty("sdk.dir")
        ?: System.getenv("ANDROID_HOME")
        ?: error("Cannot find Android SDK: set sdk.dir in local.properties or ANDROID_HOME env var")
}

val ndkVersion: String by lazy {
    val version = android.ndkVersion
    version.ifEmpty {
        File(sdkDir, "ndk").listFiles()?.maxByOrNull { it.name }?.name
        ?: error("No NDK found in $sdkDir/ndk/")
    }
}

// In ONDK mode the toolchain lives under ONDK_HOME; otherwise use the SDK's NDK.
val ndkDir: File by lazy { if (useOndk) File(ondkHome!!) else File(sdkDir, "ndk/$ndkVersion") }
val toolchainBin: File by lazy { File(ndkDir, "toolchains/llvm/prebuilt/$hostTag/bin") }
val minApi = android.defaultConfig.minSdk!!

val arBin: File by lazy { File(toolchainBin, "llvm-ar${if (hostTag.startsWith("windows")) ".exe" else ""}") }

val buildTasks = androidTargets.map { target ->
    val envTarget = target.rustTriple.replace('-', '_')
    tasks.register<Exec>("buildNativeLib_${target.abi}") {
        group = "native"
        description = "Build Rust JNI library for ${target.abi}${if (useOndk) " (ONDK)" else ""}"
        workingDir = nativeDir.asFile
        val clang = File(toolchainBin, "${target.clangPrefix}${minApi}-clang${clangSuffix}")
        val clangxx = File(toolchainBin, "${target.clangPrefix}${minApi}-clang++${clangSuffix}")
        val cmd = buildList {
            add("cargo")
            if (useOndk) add("+ondk")
            add("build"); add("--release")
            add("--target"); add(target.rustTriple)
            if (useOndk) {
                add("-Z"); add("build-std=std,panic_abort")
                add("-Z"); add("build-std-features=optimize_for_size")
            }
            add("--no-default-features"); add("--features"); add("rustls-tls")
        }
        commandLine(*cmd.toTypedArray())
        environment("CARGO_TARGET_${envTarget.uppercase()}_LINKER", clang.absolutePath)
        environment("CC_$envTarget", clang.absolutePath)
        environment("CXX_$envTarget", clangxx.absolutePath)
        environment("AR_$envTarget", arBin.absolutePath)
        environment("ANDROID_NDK_HOME", ndkDir.absolutePath)
        // Append, not prepend: target tools are absolute paths above; prepending shadows the
        // host linker (breaks ONDK's windows-gnu host build scripts).
        environment("PATH", "${System.getenv("PATH")}${File.pathSeparator}${toolchainBin.absolutePath}")
        if (useOndk) {
            // Rebuild std + LTO the C deps into the Android artifact; --target scopes it off the host.
            environment("RUSTC_BOOTSTRAP", "1")
            environment("RUSTFLAGS", "-Clinker-plugin-lto")
            environment("CFLAGS_$envTarget", "-flto")
            environment("CXXFLAGS_$envTarget", "-flto")
        }
        inputs.dir(nativeDir.dir("src"))
        inputs.file(nativeDir.file("Cargo.toml"))
        inputs.property("useOndk", useOndk)
        outputs.file(nativeDir.dir("target/${target.rustTriple}/release/libpayload_extract_jni.so"))
    }
}

val buildNativeLibForAndroid by tasks.registering {
    group = "native"
    description = "Build Rust JNI library for all Android targets"
    dependsOn(buildTasks)
}

val copyNativeLibsForAndroid by tasks.registering {
    group = "native"
    description = "Copy built .so files to jniLibs"
    dependsOn(buildNativeLibForAndroid)

    val nativeDirFile = nativeDir.asFile
    val jniLibsDirFile = jniLibsDir.asFile
    val targets = androidTargets.map { it.rustTriple to it.abi }
    doLast {
        targets.forEach { (rustTriple, abi) ->
            val src = File(nativeDirFile, "target/$rustTriple/release/libpayload_extract_jni.so")
            val destDir = File(jniLibsDirFile, abi)
            destDir.mkdirs()
            src.copyTo(File(destDir, "libpayload_extract_jni.so"), overwrite = true)
        }
    }
}

tasks.named("preBuild") {
    dependsOn(copyNativeLibsForAndroid)
}
