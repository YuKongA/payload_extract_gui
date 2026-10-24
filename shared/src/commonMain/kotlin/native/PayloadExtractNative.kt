package native

expect object PayloadExtractNative {
    fun open(input: String): Long

    /** Returns an independently owned reference to the same parsed payload session. */
    fun retain(handle: Long): Long
    fun listPartitionsJson(handle: Long, withHash: Boolean): String
    fun getMetadataJson(handle: Long): String
    fun extractPartition(handle: Long, outputDir: String, partitionName: String, threads: Int, verify: Boolean, token: Long)

    /**
     * Polls two-phase extraction progress for [token]. Returns `-1` when there is no active
     * extraction; otherwise `(phase shl 16) or permille`, where phase is 0 (downloading) or
     * 1 (extracting) and permille is 0..1000.
     */
    fun getExtractProgress(token: Long): Long
    fun close(handle: Long)
}
