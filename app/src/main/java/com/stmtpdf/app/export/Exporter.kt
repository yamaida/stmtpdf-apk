package com.stmtpdf.app.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File

object Exporter {

    /** 一次导出的落地结果：uri 用于打开/分享，path 用于告诉用户存到哪了。 */
    data class Saved(
        val uri: Uri,
        val path: String,
        val mime: String,
        val isPrivate: Boolean = false,
        val picked: Boolean = false
    )

    fun save(context: Context, base64: String, fileName: String, mime: String): Saved {
        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToMediaStore(context, bytes, fileName, mime)
        } else {
            saveToDownloads(context, bytes, fileName, mime)
        }
    }

    /** SAF 选目录后写入：路径由用户决定，无法回读完整目录，只回显示名。 */
    fun writeToDocument(context: Context, uri: Uri, base64: String, mime: String): Saved {
        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IllegalStateException("cannot open output stream")
        val name = displayName(context, uri) ?: uri.lastPathSegment ?: "export"
        return Saved(uri, name, mime, isPrivate = false, picked = true)
    }

    /**
     * 写入用户长期指定的目录（ACTION_OPEN_DOCUMENT_TREE）。
     * 目录不可用 / 无写权限时返回 null，由调用方回退到默认下载目录。
     */
    fun saveToTree(
        context: Context,
        treeUri: Uri,
        base64: String,
        fileName: String,
        mime: String
    ): Saved? = runCatching {
        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        // 标准 SAF 建文件；少数 provider 不支持时退回 insert
        val uri = android.provider.DocumentsContract.createDocument(
            context.contentResolver, parent, mime, fileName
        ) ?: context.contentResolver.insert(parent, ContentValues().apply {
            put(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME, fileName)
            put(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE, mime)
        }) ?: throw IllegalStateException("cannot create document")
        try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("cannot write document")
        } catch (t: Throwable) {
            // 写失败就删掉刚建的文件，避免目录里残留 0 字节文件
            runCatching {
                android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)
            }
            throw t
        }
        val folder = treeLabel(treeUri)
        Saved(uri, if (folder.isEmpty()) fileName else "$folder/$fileName", mime, picked = true)
    }.getOrNull()

    /** tree URI 转成可读目录名，例如 primary:Documents/Statements → Documents/Statements。 */
    fun treeLabel(treeUri: Uri): String = runCatching {
        val id = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        id.substringAfter(':', id)
    }.getOrDefault("")

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToMediaStore(
        context: Context,
        bytes: ByteArray,
        fileName: String,
        mime: String
    ): Saved {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/StmtPDF")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法创建下载文件")
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IllegalStateException("无法写入下载文件")
        return Saved(uri, "Downloads/StmtPDF/$fileName", mime)
    }

    /**
     * Android 9 及以下：写公共「下载/StmtPDF」，让用户在文件管理器里看得到；
     * 没有存储权限时退回应用私有目录，并在界面上提示改用「分享」。
     */
    private fun saveToDownloads(
        context: Context,
        bytes: ByteArray,
        fileName: String,
        mime: String
    ): Saved {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val folder = File(downloads, "StmtPDF")
        return try {
            if (!folder.exists() && !folder.mkdirs()) {
                throw IllegalStateException("cannot create folder")
            }
            val file = File(folder, fileName)
            file.writeBytes(bytes)
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
            Saved(
                FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file),
                "${downloads.name}/StmtPDF/$fileName",
                mime
            )
        } catch (e: Throwable) {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "").apply { mkdirs() }
            val file = File(dir, fileName)
            file.writeBytes(bytes)
            Saved(
                FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file),
                file.absolutePath,
                mime,
                isPrivate = true
            )
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    /** 用系统应用打开导出文件；没有可处理的应用时返回 false。 */
    fun open(context: Context, uri: Uri, mime: String): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    fun share(context: Context, uri: Uri, mime: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share"))
    }

    fun shareFile(context: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        share(context, uri, mime)
    }
}
