package ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import state.AppViewModel
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.utils.overScrollVertical
import ui.component.errorSection
import ui.component.extractSettingsSection
import ui.component.inputSection
import ui.component.partitionListSection
import ui.component.payloadInfoSection
import util.FilePicker

@Composable
fun CompactContent(
    viewModel: AppViewModel,
    innerPadding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    filePicker: FilePicker,
    selectFileTitle: String,
    selectOutputDirTitle: String,
) {
    val lazyListState = rememberLazyListState()

    LazyColumn(
        state = lazyListState,
        modifier = Modifier
            .fillMaxHeight()
            .overScrollVertical()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentPadding = innerPadding,
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

        payloadInfoSection(viewModel.metadata)

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

        errorSection(viewModel.errorMessage)
    }
}
