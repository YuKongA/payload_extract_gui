package util

import native.PayloadExtractNative
import java.io.File

actual fun initialOutputDir(): String {
    prefGet("outputDir")?.let { return it }
    val home = System.getProperty("user.home") ?: "."
    return File(home, "payload_extract_output").absolutePath
}

actual fun extractPartitionToOutput(
    handle: Long,
    outputDir: String,
    outputSubdir: String,
    partitionName: String,
    threads: Int,
    verify: Boolean,
    token: Long,
) {
    PayloadExtractNative.extractPartition(
        handle, File(outputDir, outputSubdir).path, partitionName, threads, verify, token
    )
}
