package ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import org.jetbrains.compose.resources.stringResource
import payload_extract_gui.shared.generated.resources.Res
import payload_extract_gui.shared.generated.resources.app_name
import payload_extract_gui.shared.generated.resources.select_file
import payload_extract_gui.shared.generated.resources.select_output_dir
import state.AppViewModel
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import ui.dialog.AboutDialog
import ui.dialog.PartitionDetailDialog
import ui.dialog.UrlInputDialog
import util.createFilePicker
import util.isWideScreen

@Composable
fun MainScreen() {
    val viewModel = remember { AppViewModel() }
    val scrollBehavior = MiuixScrollBehavior()
    val filePicker = remember { createFilePicker() }
    val isWide = isWideScreen()

    val selectFileTitle = stringResource(Res.string.select_file)
    val selectOutputDirTitle = stringResource(Res.string.select_output_dir)

    DisposableEffect(Unit) {
        onDispose { viewModel.dispose() }
    }

    if (isWide) {
        Scaffold { innerPadding ->
            WideScreenContent(
                viewModel = viewModel,
                innerPadding = innerPadding,
                scrollBehavior = scrollBehavior,
                filePicker = filePicker,
                selectFileTitle = selectFileTitle,
                selectOutputDirTitle = selectOutputDirTitle,
            )
            Dialogs(viewModel)
        }
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
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
            },
        ) { innerPadding ->
            CompactContent(
                viewModel = viewModel,
                innerPadding = innerPadding,
                scrollBehavior = scrollBehavior,
                filePicker = filePicker,
                selectFileTitle = selectFileTitle,
                selectOutputDirTitle = selectOutputDirTitle,
            )
            Dialogs(viewModel)
        }
    }
}

@Composable
fun Dialogs(
    viewModel: AppViewModel
) {
    UrlInputDialog(
        show = viewModel.showUrlDialog,
        onDismiss = { viewModel.showUrlDialog = false },
        onConfirm = { url ->
            viewModel.setInputUrl(url)
            viewModel.showUrlDialog = false
        },
    )
    PartitionDetailDialog(
        show = viewModel.showPartitionDetail,
        partition = viewModel.partitionDetailData,
        onDismissRequest = { viewModel.dismissPartitionDetail() },
        onDismissFinished = { viewModel.clearPartitionDetail() },
    )
}