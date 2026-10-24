package util

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilenameFilter
import javax.swing.JFileChooser

actual class FilePicker {
    actual fun pickFile(title: String, extensions: List<String>, onResult: (SelectedFile?) -> Unit) {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        if (extensions.isNotEmpty()) {
            dialog.filenameFilter = FilenameFilter { _, name ->
                extensions.any { name.endsWith(it, ignoreCase = true) }
            }
        }
        dialog.isVisible = true
        val file = dialog.file
        val dir = dialog.directory
        if (file != null && dir != null) {
            val path = File(dir, file).absolutePath
            onResult(SelectedFile(path, path))
        } else {
            onResult(null)
        }
    }

    actual fun pickDirectory(title: String, onResult: (String?) -> Unit) {
        val chooser = JFileChooser()
        chooser.dialogTitle = title
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        chooser.isAcceptAllFileFilterUsed = false
        val result = chooser.showOpenDialog(null)
        if (result == JFileChooser.APPROVE_OPTION) {
            onResult(chooser.selectedFile.absolutePath)
        } else {
            onResult(null)
        }
    }
}

actual fun createFilePicker(): FilePicker = FilePicker()
