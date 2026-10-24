package ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import model.ExtractProgress
import model.PartitionInfo
import org.jetbrains.compose.resources.stringResource
import payload_extract_gui.shared.generated.resources.Res
import payload_extract_gui.shared.generated.resources.partitions_count
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

fun LazyListScope.partitionListSection(
    partitions: List<PartitionInfo>,
    completedPartitions: Set<String>,
    failedPartitions: Map<String, String>,
    currentPartition: String?,
    partitionProgress: Map<String, ExtractProgress>,
    extractionEnabled: Boolean,
    onExtract: (String) -> Unit,
    onDetail: (PartitionInfo) -> Unit,
) {
    item(key = "partition_header") {
        SmallTitle(text = stringResource(Res.string.partitions_count, partitions.size))
    }

    itemsIndexed(
        items = partitions,
        key = { _, partition -> partition.name },
    ) { index, partition ->
        val status = when (partition.name) {
            currentPartition -> PartitionStatus.EXTRACTING
            in completedPartitions -> PartitionStatus.COMPLETED
            in failedPartitions -> PartitionStatus.FAILED
            else -> PartitionStatus.IDLE
        }
        val topCorner = if (index == 0) 16.dp else 0.dp
        val bottomCorner = if (index == partitions.lastIndex) 16.dp else 0.dp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = if (index == partitions.lastIndex) 12.dp else 0.dp)
                .squircleClip(
                    topStart = topCorner,
                    topEnd = topCorner,
                    bottomStart = bottomCorner,
                    bottomEnd = bottomCorner,
                )
                .background(MiuixTheme.colorScheme.surfaceContainer),
        ) {
            PartitionListItem(
                partition = partition,
                status = status,
                progress = partitionProgress[partition.name],
                extractionEnabled = extractionEnabled,
                onExtract = { onExtract(partition.name) },
                onDetail = { onDetail(partition) },
            )
        }
    }
}
