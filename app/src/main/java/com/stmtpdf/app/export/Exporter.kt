package com.stmtpdf.app.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

object Exporter {

    /** 保存导出文件，返回可分享的 Uri 与展示路径。 */
    fun save(context: Context, base64: String, fileName: String, mime: String): Pair<Uri, String> {
        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
            uri to "下载/StmtPDF/$fileName"
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "").apply { mkdirs() }
            val file = File(dir, fileName)
            file.writeBytes(bytes)
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file) to file.absolutePath
        }
    }

    fun share(context: Context, uri: Uri, mime: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享文件"))
    }

    fun shareFile(context: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        share(context, uri, mime)
    }
}
