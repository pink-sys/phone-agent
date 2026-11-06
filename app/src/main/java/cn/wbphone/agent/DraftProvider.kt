package cn.wbphone.agent

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * 相机临时文件的写通道。
 *
 * 系统相机要一个「能写进去的 URI」当 EXTRA_OUTPUT。Android 7 起不能给 file://，
 * 官方做法是 FileProvider（要 androidx）——这个工程手工编译、零依赖，
 * 所以自己写一个：只管 cacheDir/attach 一个目录，只给相机临时授权。
 *
 * 与 ArtifactProvider 分开是刻意的：那个是只读、面向外部应用长期分享产物；
 * 这个是读写、只给系统相机一次性的写入位置，权限面越小越好。
 * exported=false + grantUriPermissions，别的应用拿不到持久访问。
 */
class DraftProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val name = uri.encodedPath?.trimStart('/') ?: throw FileNotFoundException("空路径")
        // 只允许单层文件名：挡住 ../ 和子目录
        if (name.isEmpty() || name.contains('/') || name.contains("..")) {
            throw FileNotFoundException("非法路径：$name")
        }
        val ctx = context ?: throw FileNotFoundException("没有 context")
        val dir = File(ctx.cacheDir, "attach").apply { mkdirs() }.canonicalFile
        val f = File(dir, name).canonicalFile
        if (!f.path.startsWith(dir.path + File.separator)) throw FileNotFoundException("越界：$name")

        val write = mode.startsWith("w")
        val flags = if (write) {
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or
                ParcelFileDescriptor.MODE_WRITE_ONLY
        } else {
            ParcelFileDescriptor.MODE_READ_ONLY
        }
        return ParcelFileDescriptor.open(f, flags)
    }

    override fun getType(uri: Uri): String =
        when (uri.encodedPath?.substringAfterLast('.', "")?.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            else -> "application/octet-stream"
        }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
