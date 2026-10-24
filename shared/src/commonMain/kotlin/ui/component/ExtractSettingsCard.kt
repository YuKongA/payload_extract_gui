package ui.component

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import payload_extract_gui.shared.generated.resources.Res
import payload_extract_gui.shared.generated.resources.select_output_dir_first
import payload_extract_gui.shared.generated.resources.output_directory
import payload_extract_gui.shared.generated.resources.settings
import payload_extract_gui.shared.generated.resources.verify_sha256
import payload_extract_gui.shared.generated.resources.verify_sha256_summary
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

fun LazyListScope.extractSettingsSection(
    outputDir: String,
    threadCount: Int,
    verifyHash: Boolean,
    onSelectOutputDir: () -> Unit,
    onThreadCountChange: (Int) -> Unit,
    onVerifyHashChange: (Boolean) -> Unit,
) {
    item(key = "extract_settings") {
        SmallTitle(text = stringResource(Res.string.settings))
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
        ) {
            ArrowPreference(
                title = stringResource(Res.string.output_directory),
                summary = outputDir.ifBlank { stringResource(Res.string.select_output_dir_first) },
                onClick = onSelectOutputDir,
            )
            SwitchPreference(
                title = stringResource(Res.string.verify_sha256),
                summary = stringResource(Res.string.verify_sha256_summary),
                checked = verifyHash,
                onCheckedChange = onVerifyHashChange,
            )
        }
    }
}
