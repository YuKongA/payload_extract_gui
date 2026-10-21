import org.gradle.api.Project

object ProjectConfig {
    const val APP_NAME = "payload-extract-gui"
    const val PACKAGE_NAME = "top.yukonga.payload_extract_gui"
    const val VERSION_NAME = "1.0.0"

    object Android {
        const val TARGET_SDK = 37
        const val MIN_SDK = 26
        const val COMPILE_SDK = 37
        const val COMPILE_SDK_MINOR = 0
    }
}

private const val FALLBACK_VERSION_CODE = 1

/** 提交数作为 versionCode。调用方应只求值一次。 */
fun Project.getGitVersionCode(): Int =
    runCatching {
        providers.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
        }.standardOutput.asText.get().trim().toInt()
    }.getOrDefault(FALLBACK_VERSION_CODE)
