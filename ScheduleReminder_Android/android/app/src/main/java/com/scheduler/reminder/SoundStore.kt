package com.scheduler.reminder

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

/** Copy imported sounds into private storage. URI remains stable for NotificationChannel. */
object SoundStore {
    private const val MAX_BYTES = 8L * 1024 * 1024
    private val allowedExtensions = setOf("mp3", "ogg", "wav", "m4a", "aac")
    private val safeFileName = Regex("^[0-9a-f]{32}\\.(mp3|ogg|wav|m4a|aac)$")

    data class Imported(val id: String, val displayName: String)

    fun importSound(context: Context, source: Uri): Imported {
        val resolver = context.contentResolver
        val mime = (resolver.getType(source) ?: "").lowercase()
        val originalName = resolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: "Âm thanh đã chọn"
        val extension = when {
            mime.contains("mpeg") || mime.contains("mp3") -> "mp3"
            mime.contains("ogg") -> "ogg"
            mime.contains("wav") || mime.contains("wave") -> "wav"
            mime.contains("mp4") || mime.contains("m4a") -> "m4a"
            mime.contains("aac") -> "aac"
            else -> originalName.substringAfterLast('.', "").lowercase()
        }
        require((mime.startsWith("audio/") || mime.isBlank()) && extension in allowedExtensions) {
            "Chỉ hỗ trợ MP3, OGG, WAV, M4A hoặc AAC"
        }
        val id = UUID.randomUUID().toString().replace("-", "") + ".$extension"
        val folder = File(context.applicationContext.filesDir, "sounds").apply { mkdirs() }
        val target = File(folder, id)
        try {
            val stream = resolver.openInputStream(source)
                ?: throw IllegalArgumentException("Không đọc được file âm thanh")
            stream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var size = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= MAX_BYTES) { "File âm thanh phải nhỏ hơn 8 MB" }
                        output.write(buffer, 0, count)
                    }
                    require(size > 0) { "File âm thanh trống" }
                }
            }
            return Imported(id, originalName.take(150))
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    fun soundUri(context: Context, id: String): Uri {
        require(safeFileName.matches(id)) { "Tên file âm thanh không hợp lệ" }
        return Uri.parse("content://${context.packageName}.sounds/audio/$id")
    }

    internal fun soundFile(context: Context, id: String): File? =
        if (safeFileName.matches(id)) File(context.filesDir, "sounds/$id").takeIf { it.isFile } else null
}

/** Read-only, minimal ContentProvider accessible to Android notification sound service. */
class ReminderSoundProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = when (uri.lastPathSegment?.substringAfterLast('.')) {
        "mp3" -> "audio/mpeg"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        else -> null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r" || uri.pathSegments.size != 2 || uri.pathSegments[0] != "audio") {
            throw java.io.FileNotFoundException("Chỉ được đọc file âm thanh")
        }
        val app = context ?: throw java.io.FileNotFoundException("Không có Context")
        val file = SoundStore.soundFile(app, uri.pathSegments[1])
            ?: throw java.io.FileNotFoundException("Không tồn tại âm thanh")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0
}