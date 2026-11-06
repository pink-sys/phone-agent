package cn.wbphone.agent

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * 产物分享通道。
 *
 * Android 7 起把 file:// URI 传给别的应用会直接崩（FileUriExposedException），
 * 正经做法是 FileProvider——但那要 androidx 依赖，这个工程是手工编译的、没有。
 * 自己写一个只读 provider，五十行，零依赖，效果一样：别的应用（WPS、浏览器、
 * 文件管理器）拿到 content:// URI 就能打开工作台里的产物。
 *
 * 只读、沙箱内、canonical 前缀校验，三样缺一不可。
 */
class ArtifactProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    /** 打开的永远是工作台里的文件。路径来自 URI 的 path 部分。 */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw IllegalArgumentException("只读")
        val rel = uri.encodedPath?.trimStart('/')
            ?: throw FileNotFoundException("空路径")
        val ctx = context ?: throw FileNotFoundException("没有 context")
        val dir = (ctx.getExternalFilesDir(null) ?: ctx.filesDir).canonicalFile
        val f = File(dir, rel).canonicalFile
        // canonical 之后再比前缀：../ 和符号链接都逃不出去
        if (f != dir && !f.path.startsWith(dir.path + File.separator)) {
            throw FileNotFoundException("越界：$rel")
        }
        if (!f.exists() || !f.isFile) throw FileNotFoundException("不存在：$rel")
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String {
        val ext = uri.encodedPath?.substringAfterLast('.', "")?.lowercase() ?: ""
        // 常见产物扩展名给准确的 MIME，别的应用才知道拿什么打开
        val fromMap = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        return when {
            ext == "md" -> "text/markdown"
            fromMap != null -> fromMap
            else -> "application/octet-stream"
        }
    }

    /** 没有数据库。这四个操作出现即误用，直接拒绝。 */
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
