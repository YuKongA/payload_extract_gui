package util

/** Restores an authorized destination, or returns empty when selection is required. */
expect fun initialOutputDir(): String

/** Blocking extraction from a retained payload session into the selected per-payload subfolder. */
expect fun extractPartitionToOutput(
    handle: Long,
    outputDir: String,
    outputSubdir: String,
    partitionName: String,
    threads: Int,
    verify: Boolean,
    token: Long,
)
