package native

import java.io.File

actual object PayloadExtractNative {
    init {
        // java.library.path is set by Gradle to include the native output directory,
        // so System.loadLibrary should work directly for development.
        // For packaged distributions, compose.application.resources.dir is used.
        val libName = System.mapLibraryName("payload_extract_jni")
        val resourcesDir = System.getProperty("compose.application.resources.dir")
        val resourceLib = if (resourcesDir != null) File(resourcesDir, libName) else null

        if (resourceLib != null && resourceLib.exists()) {
            System.load(resourceLib.absolutePath)
        } else {
            System.loadLibrary("payload_extract_jni")
        }
    }

    actual external fun open(input: String): Long
    actual external fun retain(handle: Long): Long
    actual external fun listPartitionsJson(handle: Long, withHash: Boolean): String
    actual external fun getMetadataJson(handle: Long): String
    actual external fun extractPartition(handle: Long, outputDir: String, partitionName: String, threads: Int, verify: Boolean, token: Long)
    actual external fun getExtractProgress(token: Long): Long
    actual external fun close(handle: Long)
}
