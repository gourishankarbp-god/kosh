package com.prash.kosh.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/** Files attached to policies: kept in app-private storage (not visible to other apps) and synced to Drive. */
object Documents {
    fun dir(context: Context) = File(context.filesDir, "documents").apply { mkdirs() }
    fun file(context: Context, d: DocumentEntity) = File(dir(context), d.fileKey)

    suspend fun attach(context: Context, repo: Repository, investmentId: Long, uri: Uri) {
        val cr = context.contentResolver
        var name = "document"; var size = 0L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) { name = c.getString(0) ?: name; size = c.getLong(1) }
        }
        if (size > 10L * 1024 * 1024) { Toast.makeText(context, "Files up to 10 MB", Toast.LENGTH_SHORT).show(); return }
        val key = UUID.randomUUID().toString() + "." + name.substringAfterLast('.', "bin").take(5)
        val out = File(dir(context), key)
        cr.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: return
        repo.db.extras().insertDocument(DocumentEntity(investmentId = investmentId, name = name, mime = cr.getType(uri) ?: "application/octet-stream",
            sizeBytes = out.length(), fileKey = key, addedAt = System.currentTimeMillis()))
    }

    suspend fun delete(context: Context, repo: Repository, d: DocumentEntity) {
        repo.db.extras().deleteDocument(d.id)
        file(context, d).delete()
    }

    fun open(context: Context, d: DocumentEntity) {
        val f = file(context, d)
        if (!f.exists()) { Toast.makeText(context, "Still downloading from Drive. Try again shortly.", Toast.LENGTH_SHORT).show(); return }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, d.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
