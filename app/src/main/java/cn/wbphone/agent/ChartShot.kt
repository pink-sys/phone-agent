package cn.wbphone.agent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * chart_write 的像素层：[Workbench.ChartEngine] 的 Android 实现。
 *
 * 布局全在 [ChartText]（纯 JVM，有测试）——这里不做任何几何计算，只把计划里的
 * 五种图元（rect/line/poly/circle/arc/text）按 JSON 照单画到白底画布上，出 PNG。
 * 图元解析失败（计划损坏）整张拒绝，不画半张——半张图比没有图更误导人。
 */
object ChartShot : Workbench.ChartEngine {

    override fun write(out: File, plan: String, width: Int, height: Int): String = try {
        val p = JSONObject(plan)
        val w = p.optInt("w", width).coerceAtMost(4096)
        val h = p.optInt("h", height).coerceAtMost(4096)
        out.parentFile?.mkdirs()
        out.writeBytes(render(p, w, h))
        JSONObject()
            .put("ok", true)
            .put("out", out.name)
            .put("width", w)
            .put("height", h)
            .put("bytes", out.length())
            .put("note", "图表 PNG 已生成。插进 Word：docx_write 的 {type:\"image\", path:\"${out.name}\"}")
            .toString()
    } catch (e: Exception) {
        JSONObject().put("ok", false)
            .put("code", "RENDER_FAILED")
            .put("error", "渲染失败：${e.message ?: e.javaClass.simpleName}")
            .toString()
    }

    private fun render(plan: JSONObject, w: Int, h: Int): ByteArray {
        val items = plan.optJSONArray("items") ?: throw IllegalArgumentException("计划里没有 items")
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bg = try { Color.parseColor(plan.optString("bg", "#FFFFFF")) } catch (_: Exception) { Color.WHITE }
        canvas.drawColor(bg)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until items.length()) {
            draw(canvas, paint, items.optJSONObject(i) ?: continue)
        }

        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        bmp.recycle()
        return bos.toByteArray()
    }

    private fun draw(canvas: Canvas, paint: Paint, it: JSONObject) {
        when (it.optString("op")) {
            "rect" -> {
                paint.style = Paint.Style.FILL
                paint.color = color(it.optString("fill"))
                val r = it.optDouble("r", 0.0).toFloat()
                canvas.drawRoundRect(
                    RectF(it.optDouble("x", 0.0).toFloat(), it.optDouble("y", 0.0).toFloat(),
                        (it.optDouble("x", 0.0) + it.optDouble("w", 0.0)).toFloat(),
                        (it.optDouble("y", 0.0) + it.optDouble("h", 0.0)).toFloat()),
                    r, r, paint)
            }
            "line" -> {
                paint.style = Paint.Style.STROKE
                paint.color = color(it.optString("color"))
                paint.strokeWidth = it.optDouble("width", 1.0).toFloat()
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(it.optDouble("x1", 0.0).toFloat(), it.optDouble("y1", 0.0).toFloat(),
                    it.optDouble("x2", 0.0).toFloat(), it.optDouble("y2", 0.0).toFloat(), paint)
            }
            "poly" -> {
                val pts = it.optJSONArray("pts") ?: return
                if (pts.length() < 4) return
                val path = Path()
                path.moveTo(pts.getDouble(0).toFloat(), pts.getDouble(1).toFloat())
                for (i in 1 until pts.length() / 2) {
                    path.lineTo(pts.getDouble(i * 2).toFloat(), pts.getDouble(i * 2 + 1).toFloat())
                }
                paint.style = Paint.Style.STROKE
                paint.color = color(it.optString("color"))
                paint.strokeWidth = it.optDouble("width", 2.0).toFloat()
                paint.strokeJoin = Paint.Join.ROUND
                canvas.drawPath(path, paint)
            }
            "circle" -> {
                paint.style = Paint.Style.FILL
                paint.color = color(it.optString("fill"))
                canvas.drawCircle(it.optDouble("cx", 0.0).toFloat(), it.optDouble("cy", 0.0).toFloat(),
                    it.optDouble("r", 0.0).toFloat(), paint)
            }
            "arc" -> {
                paint.style = Paint.Style.FILL
                paint.color = color(it.optString("fill"))
                val r = it.optDouble("r", 0.0).toFloat()
                canvas.drawArc(
                    RectF((it.optDouble("cx", 0.0) - r).toFloat(), (it.optDouble("cy", 0.0) - r).toFloat(),
                        (it.optDouble("cx", 0.0) + r).toFloat(), (it.optDouble("cy", 0.0) + r).toFloat()),
                    it.optDouble("start", 0.0).toFloat(), it.optDouble("sweep", 0.0).toFloat(),
                    true, paint)
            }
            "text" -> {
                paint.style = Paint.Style.FILL
                paint.color = color(it.optString("color"))
                paint.textSize = it.optInt("size", 24).toFloat()
                paint.typeface = if (it.optBoolean("bold")) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                paint.textAlign = when (it.optString("align")) {
                    "c" -> Paint.Align.CENTER
                    "r" -> Paint.Align.RIGHT
                    else -> Paint.Align.LEFT
                }
                canvas.drawText(it.optString("s", ""), it.optDouble("x", 0.0).toFloat(),
                    it.optDouble("y", 0.0).toFloat(), paint)
            }
        }
    }

    /** "#RRGGBB" → int。坏颜色回落墨灰，不抛——一张图不该因为一个色值白屏。 */
    private fun color(s: String): Int = try {
        Color.parseColor(s)
    } catch (_: Exception) {
        0xFF444441.toInt()
    }
}
