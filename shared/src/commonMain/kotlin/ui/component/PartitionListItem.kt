package ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import model.ExtractPhase
import model.ExtractProgress
import model.PartitionInfo
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import payload_extract_gui.shared.generated.resources.Res
import payload_extract_gui.shared.generated.resources.done
import payload_extract_gui.shared.generated.resources.downloading
import payload_extract_gui.shared.generated.resources.extract
import payload_extract_gui.shared.generated.resources.extracting
import payload_extract_gui.shared.generated.resources.ic_img
import payload_extract_gui.shared.generated.resources.retry
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import util.formatFileSize

enum class PartitionStatus {
    IDLE, EXTRACTING, COMPLETED, FAILED,
}

@Composable
fun PartitionListItem(
    partition: PartitionInfo,
    status: PartitionStatus,
    progress: ExtractProgress?,
    extractionEnabled: Boolean,
    onExtract: () -> Unit,
    onDetail: () -> Unit,
) {
    BasicComponent(
        title = partition.name,
        summary = buildString {
            append(formatFileSize(partition.size))
            partition.hash?.let { append("  (${it.take(10)})") }
        },
        startAction = {
            Image(
                painter = painterResource(Res.drawable.ic_img),
                contentDescription = partition.name,
                modifier = Modifier
                    .padding(end = 5.dp)
                    .size(40.dp),
            )
        },
        endActions = {
            when (status) {
                PartitionStatus.EXTRACTING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(
                                if (progress?.phase == ExtractPhase.DOWNLOAD) Res.string.downloading
                                else Res.string.extracting
                            ),
                            color = MiuixTheme.colorScheme.onSecondaryVariant,
                            style = MiuixTheme.textStyles.body2,
                        )
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(
                            progress = progress?.fraction,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }

                PartitionStatus.COMPLETED -> {
                    Text(
                        text = stringResource(Res.string.done),
                        color = MiuixTheme.colorScheme.primary,
                        style = MiuixTheme.textStyles.body2,
                    )
                }

                PartitionStatus.FAILED -> {
                    Button(
                        onClick = onExtract,
                        enabled = extractionEnabled,
                        colors = ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.primary.copy(0.08f),
                            contentColor = MiuixTheme.colorScheme.primary,
                        ),
                        minHeight = 32.dp,
                        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = stringResource(Res.string.retry),
                            style = MiuixTheme.textStyles.body2,
                        )
                    }
                }

                PartitionStatus.IDLE -> {
                    Button(
                        onClick = onExtract,
                        enabled = extractionEnabled,
                        colors = ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.primary.copy(0.08f),
                            contentColor = MiuixTheme.colorScheme.primary,
                        ),
                        minHeight = 32.dp,
                        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = stringResource(Res.string.extract),
                            style = MiuixTheme.textStyles.body2,
                        )
                    }
                }
            }
        },
        onClick = onDetail,
    )
}
