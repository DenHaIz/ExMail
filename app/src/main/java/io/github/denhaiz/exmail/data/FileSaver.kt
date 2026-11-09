package io.github.denhaiz.exmail.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Сохранение файлов в общую папку «Загрузки» (Download/ExMail). На Android 10+ — напрямую,
 * на 8–9 — через системный диалог выбора места (без разрешения на доступ к памяти).
 */
object FileSaver {
    const val FOLDER = "ExMail"

    fun safeName(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim()
        .takeUnless { it.isBlank() || it.all { c -> c == '.' } } ?: "file" // «.» и «..» — это папки, а не имя файла

    fun mimeOf(name: String, fallback: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: fallback.takeIf { it.isNotBlank() } ?: "application/octet-stream"

    /** Android 10+: копирует [file] в Download/ExMail и возвращает адрес сохранённого файла. */
    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun saveToDownloads(context: Context, file: File, name: String, mime: String): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, safeName(name))
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Не удалось создать файл в «Загрузках»")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("Не удалось записать файл")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /** Копирует [file] по адресу, выбранному пользователем в системном диалоге. */
    suspend fun copyTo(context: Context, file: File, target: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(target)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ?: throw IllegalStateException("Не удалось записать файл")
    }
}
