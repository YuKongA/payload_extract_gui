package util

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import java.lang.ref.WeakReference

actual class FilePicker(private val activity: Activity) {
    private var fileCallback: ((SelectedFile?) -> Unit)? = null
    private var dirCallback: ((String?) -> Unit)? = null
    private var openedFd: ParcelFileDescriptor? = null

    var filePickerLauncher: ActivityResultLauncher<Intent>? = null
    var dirPickerLauncher: ActivityResultLauncher<Uri?>? = null

    fun onFileResult(uri: Uri?) {
        val file = uri?.let { resolveFileFromUri(it) }
        fileCallback?.invoke(file)
        fileCallback = null
    }

    fun onDirResult(uri: Uri?) {
        val treeUri = uri?.let {
            activity.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            it.toString()
        }
        dirCallback?.invoke(treeUri)
        dirCallback = null
    }

    actual fun pickFile(title: String, extensions: List<String>, onResult: (SelectedFile?) -> Unit) {
        fileCallback = onResult
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_TITLE, title)
        }
        filePickerLauncher?.launch(intent) ?: onResult(null)
    }

    actual fun pickDirectory(title: String, onResult: (String?) -> Unit) {
        dirCallback = onResult
        dirPickerLauncher?.launch(null) ?: onResult(null)
    }

    fun closeOpenedFd() {
        openedFd?.close()
        openedFd = null
    }

    private fun resolveFileFromUri(uri: Uri): SelectedFile {
        val displayName = activity.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        ).use { cursor ->
            if (cursor != null && cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            } else null
        }?.takeIf { it.isNotBlank() } ?: uri.toString()
        closeOpenedFd()
        val pfd = activity.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Cannot open file descriptor for $uri")
        openedFd = pfd
        return SelectedFile("/proc/self/fd/${pfd.fd}", displayName)
    }

}

private var filePickerRef: WeakReference<FilePicker>? = null

fun initFilePicker(activity: ComponentActivity) {
    val picker = FilePicker(activity)

    picker.filePickerLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        picker.onFileResult(result.data?.data)
    }

    picker.dirPickerLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        picker.onDirResult(uri)
    }

    filePickerRef = WeakReference(picker)
}

actual fun createFilePicker(): FilePicker {
    return filePickerRef?.get() ?: error("FilePicker not initialized. Call initFilePicker() in Activity.onCreate()")
}
