package util

data class SelectedFile(val path: String, val displayName: String)

expect class FilePicker {
    fun pickFile(title: String, extensions: List<String>, onResult: (SelectedFile?) -> Unit)
    fun pickDirectory(title: String, onResult: (String?) -> Unit)
}

expect fun createFilePicker(): FilePicker
