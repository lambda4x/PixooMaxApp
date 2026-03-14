package de.pixoo

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

data class PixooItem(
    val uriString: String,
    val description: String,
    val isInternal: Boolean = false
)

fun saveImageToInternal(context: Context, uri: Uri): String {
    val inputStream = context.contentResolver.openInputStream(uri)
    val fileName = "pixoo_${System.currentTimeMillis()}.jpg"
    val file = File(context.filesDir, fileName)
    val outputStream = FileOutputStream(file)
    inputStream?.use { input ->
        outputStream.use { output ->
            input.copyTo(output)
        }
    }
    return file.absolutePath
}
