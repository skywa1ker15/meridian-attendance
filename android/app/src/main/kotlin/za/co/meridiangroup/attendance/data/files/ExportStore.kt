package za.co.meridiangroup.attendance.data.files

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody

/**
 * Report / POPIA export files. Downloads are written to `cacheDir/exports/` (never backed up, cleared by the OS
 * when space is low) and handed to other apps through the FileProvider declared in the manifest.
 */
class ExportStore(private val context: Context) {
    private val dir: File get() = File(context.cacheDir, "exports").apply { mkdirs() }

    suspend fun save(body: ResponseBody, fileName: String): File = withContext(Dispatchers.IO) {
        pruneOld()
        val safe = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "export" }
        val file = File(dir, safe)
        body.use { b -> file.outputStream().use { out -> b.byteStream().copyTo(out) } }
        file
    }

    fun uriFor(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** Android share sheet (email, Drive, WhatsApp…). */
    fun shareIntent(file: File, mimeType: String): Intent {
        val uri = uriFor(file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, file.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** "Open with…" a viewer app (spreadsheet, PDF reader). Returns false when no app can open the type. */
    fun open(activityContext: Context, file: File, mimeType: String): Boolean {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(file), mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            activityContext.startActivity(Intent.createChooser(view, file.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /** Keeps the cache small: removes exports older than a day. */
    private fun pruneOld() {
        val cutoff = System.currentTimeMillis() - 24 * 3_600_000L
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    companion object {
        fun mimeFor(format: String): String = when (format.lowercase()) {
            "csv" -> "text/csv"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "pdf" -> "application/pdf"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
    }
}
