package cn.wbphone.agent

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 附件：相机拍照 / 相册选图。
 *
 * 走系统相机和系统图片选择器，**不申请任何存储权限**——
 * ACTION_IMAGE_CAPTURE 把结果写进我们的 cache 目录（用 DraftProvider 给临时写权限），
 * ACTION_GET_CONTENT 由系统选择器自己带读权限。用户装完就能用，不用去设置里点。
 *
 * 图片一律压到长边 [MAX_DIM] 再 base64：原图动辄 4000×3000、几 MB，
 * 直接塞进请求既烧 token 又慢；1100 长边对「看图说话 / 读截屏文字」足够。
 */
object AttachPicker {

    const val REQ_CAMERA = 4101
    const val REQ_GALLERY = 4102
    const val REQ_FILE = 4103

    /** DraftProvider 的 authority，和 AndroidManifest 里保持一致。 */
    const val AUTHORITY = "cn.wbphone.agent.drafts"

    /** 长边上限（像素）与 JPEG 质量。 */
    const val MAX_DIM = 1100
    const val QUALITY = 82

    /** 相机这次要写到哪个 URI——onActivityResult 里回来时相机不一定回传 data，得自己记。 */
    @Volatile
    private var lastCameraUri: Uri? = null

    fun lastCamera(): Uri? = lastCameraUri

    private fun draftDir(act: Activity): File =
        File(act.cacheDir, "attach").apply { mkdirs() }

    /**
     * 拍照意图。没有相机应用时返回 null，调用方提示一句就好。
     * EXTRA_OUTPUT 给的是 content://，相机对着它写——这就是 DraftProvider 存在的理由。
     */
    fun cameraIntent(act: Activity): Intent? {
        val f = File(draftDir(act), "cap_${System.currentTimeMillis()}.jpg")
        val uri = Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .encodedPath("/" + f.name)
            .build()
        lastCameraUri = uri
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return if (i.resolveActivity(act.packageManager) == null) null else i
    }

    /** 相册：系统图片选择器，EXTRA_ALLOW_MULTIPLE 让它支持一次勾多张（部分老机型会忽略，回退单选也能用）。 */
    fun galleryIntent(): Intent =
        Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }

    /**
     * 文件：系统文件选择器，文档/文本/代码都从这走。
     * type 用 * / * 让所有文档提供器都出现（DocumentsUI、网盘、文件管理器），
     * 格式对不对由我们读完再判断——MIME 白名单在国产文件管理器上经常形同虚设。
     */
    fun fileIntent(): Intent =
        Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }

    /** 压缩后的图片 + 用于气泡的缩略图。 */
    data class Picked(val dataUrl: String, val thumb: Bitmap, val name: String)

    /**
     * 读 URI → 降采样 → 压成 JPEG → data URL。
     * 失败（不是图片、流打不开、OOM）返回 null，调用方提示「换一张」。
     */
    fun pick(act: Activity, uri: Uri, maxDim: Int = MAX_DIM, quality: Int = QUALITY): Picked? = try {
        // 先量尺寸：直接 decode 一张 8000 万像素的图会 OOM
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        act.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = act.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (raw == null) {
                null
            } else {
                val scaled = scaleDown(raw, maxDim)
                if (scaled !== raw) raw.recycle()
                // 白底：PNG 带透明通道时压 JPEG 会把透明处变黑，垫一层白纸
                val flat = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
                Canvas(flat).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(scaled, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                }
                val out = ByteArrayOutputStream()
                flat.compress(Bitmap.CompressFormat.JPEG, quality, out)
                val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                val thumb = Bitmap.createScaledBitmap(
                    flat, 220, (220f * flat.height / flat.width).toInt().coerceAtLeast(1), true)
                Picked("data:image/jpeg;base64,$b64", thumb, nameOf(act, uri))
            }
        }
    } catch (e: OutOfMemoryError) {
        null
    } catch (e: Exception) {
        null
    }

    private fun scaleDown(bmp: Bitmap, maxDim: Int): Bitmap {
        val long = maxOf(bmp.width, bmp.height)
        if (long <= maxDim) return bmp
        val ratio = maxDim.toFloat() / long
        return Bitmap.createScaledBitmap(
            bmp, (bmp.width * ratio).toInt().coerceAtLeast(1),
            (bmp.height * ratio).toInt().coerceAtLeast(1), true)
    }

    /** 给界面显示个名字（相册图通常没有文件名，就报尺寸）。 */
    fun displayName(act: Activity, uri: Uri, fallback: String = "图片"): String {
        val fromProvider = runCatching {
            act.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()
        return fromProvider?.takeIf { it.isNotBlank() } ?: fallback
    }

    private fun nameOf(act: Activity, uri: Uri): String = displayName(act, uri)

    /**
     * 把选中的文件拷进 cache/attach：FileText 和 PdfRenderer 都只认本地文件，
     * content:// 的流读两次不可靠。超过 FileText.MAX_BYTES 返回 null（调用方提示太大）。
     */
    fun copyToCache(act: Activity, uri: Uri, name: String): File? = try {
        val out = File(draftDir(act), "doc_${System.currentTimeMillis()}_${safeName(name)}")
        val sink = out.outputStream()
        var total = 0L
        val ok = act.contentResolver.openInputStream(uri)?.use { src ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                total += n
                if (total > FileText.MAX_BYTES) {
                    sink.close()
                    out.delete()
                    return null
                }
                sink.write(buf, 0, n)
            }
            true
        } ?: false
        sink.close()
        if (ok && out.exists() && out.length() > 0) out else { out.delete(); null }
    } catch (e: Exception) {
        null
    }

    /** 文件名里的路径分隔符和非法字符清掉，防缓存文件名穿越。 */
    private fun safeName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^\\w().\\-\\u4e00-\\u9fa5]+"), "_")
            .take(80).ifEmpty { "file" }

    /** camera 写完后把临时文件清掉（缩略图和数据都已经在内存里）。 */
    fun cleanup(act: Activity) {
        draftDir(act).listFiles()?.forEach { f ->
            if (System.currentTimeMillis() - f.lastModified() > 60 * 60 * 1000L) f.delete()
        }
    }
}
