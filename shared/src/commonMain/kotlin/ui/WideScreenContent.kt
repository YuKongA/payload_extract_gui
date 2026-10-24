package ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import payload_extract_gui.shared.generated.resources.Res
import payload_extract_gui.shared.generated.resources.app_name
import payload_extract_gui.shared.generated.resources.icon
import state.AppViewModel
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.VerticalDivider
import top.yukonga.miuix.kmp.utils.overScrollVertical
import ui.component.errorSection
import ui.component.extractSettingsSection
import ui.component.inputSection
import ui.component.partitionListSection
import ui.component.payloadInfoSection
import ui.dialog.AboutDialog
import util.FilePicker

@Composable
fun WideScreenContent(
    viewModel: AppViewModel,
    innerPadding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    filePicker: FilePicker,
    selectFileTitle: String,
    selectOutputDirTitle: String,
) {
    Row {
        // Left pane: TopAppBar + Input + Settings
        Column(
            modifier = Modifier
                .weight(0.42f)
                .fillMaxHeight(),
        ) {
            SmallTopAppBar(
                title = stringResource(Res.string.app_name),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    AboutDialog(
                        show = viewModel.showAboutDialog,
                        onShow = { viewModel.showAboutDialog = true },
                        onDismissRequest = { viewModel.showAboutDialog = false },
                    )
                },
            )
            HorizontalDivider()
            val leftListState = rememberLazyListState()
            LazyColumn(
                state = leftListState,
                modifier = Modifier
                    .fillMaxHeight()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
            ) {
                inputSection(
                    inputDisplayName = viewModel.inputDisplayName,
                    isLoading = viewModel.isLoading,
                    onSelectFile = {
                        filePicker.pickFile(selectFileTitle, listOf(".bin", ".zip")) { file ->
                            if (file != null) viewModel.selectFile(file)
                        }
                    },
                    onInputUrl = { viewModel.showUrlDialog = true },
                    onParse = { viewModel.loadPayload() },
                )

                if (viewModel.partitions.isNotEmpty()) {
                    extractSettingsSection(
                        outputDir = viewModel.outputDir,
                        threadCount = viewModel.threadCount,
                        verifyHash = viewModel.verifyHash,
                        onSelectOutputDir = {
                            filePicker.pickDirectory(selectOutputDirTitle) { path ->
                                if (path != null) viewModel.updateOutputDir(path)
                            }
                        },
                        onThreadCountChange = { viewModel.threadCount = it },
                        onVerifyHashChange = { viewModel.updateVerifyHash(it) },
                    )
                }

                errorSection(viewModel.errorMessage)
            }
        }

        VerticalDivider(modifier = Modifier.fillMaxHeight())

        // Right pane: OTA Info + Partitions (or Logo when empty)
        Box(
            modifier = Modifier
                .weight(1f - 0.42f)
                .fillMaxHeight(),
        ) {
            if (viewModel.metadata == null && viewModel.partitions.isEmpty()) {
                Image(
                    painter = painterResource(Res.drawable.icon),
                    contentDescription = "Logo",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(128.dp),
                )
            }

            val rightListState = rememberLazyListState()
            LazyColumn(
                state = rightListState,
                modifier = Modifier
                    .fillMaxHeight()
                    .overScrollVertical(),
                contentPadding = innerPadding,
            ) {
                payloadInfoSection(viewModel.metadata)

                if (viewModel.partitions.isNotEmpty()) {
                    partitionListSection(
                        partitions = viewModel.filteredPartitions,
                        completedPartitions = viewModel.completedPartitions,
                        failedPartitions = viewModel.failedPartitions,
                        currentPartition = viewModel.currentPartition,
                        partitionProgress = viewModel.partitionProgress,
                        extractionEnabled = viewModel.outputDir.isNotBlank(),
                        onExtract = { viewModel.extractPartition(it) },
                        onDetail = { viewModel.openPartitionDetail(it) },
                    )
                }
            }
        }
    }
}
