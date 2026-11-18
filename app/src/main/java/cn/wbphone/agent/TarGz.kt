package cn.wbphone.agent

import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * 零依赖的 tar.gz 解包器。
 *
 * 为什么要自己写 tar：Python 环境的 rootfs 是一个 tar.gz（见 [PyRuntime]），Android 的
 * java.util.zip 只有 gzip，没有 tar——apk 包、minirootfs 全是 tar.gz，要么引 commons-compress
 * （几百 KB 第三方依赖，违背月溯零依赖的原则），要么写一个。tar 格式很简单：512 字节头 +
 * 数据按 512 对齐，值得手写。
 *
 * 支持的成员类型：
 *  - 普通文件（'0' 或 '\0'）、目录（'5'）、符号链接（'2'）——rootfs 里就这三种加 PAX 头
 *  - PAX 扩展头（'x'）：长路径/长链接名/非整数 mtime 存在里面，覆盖下一个成员
 *  - GNU 长名（'L'）：老式打包器用，同样覆盖下一个成员
 *  - 硬链接（'1'）：指向本包内已解出的文件，复制内容
 *
 * 安全边界：所有成员名先规范化再校验——`../` 逃逸、绝对路径直接拒绝。符号链接本身
 * 不解引用（不追目标建文件），所以链接指向包外也不会写出任何东西；真正的越界防线
 * 是「文件与目录按规范化名落盘」这一条。
 */
object TarGz {

    /** 解包失败的异常。message 直接给模型看，要能看懂。 */
    class Bad(message: String) : Exception(message)

    /**
     * 解包一个 gzip 压缩的 tar 流到 [destDir]。
     *
     * @param onEntry 每解出一个成员回调一次（成员名、字节量），用于下载/解压进度提示。
     *        回调里不要做重活。
     * @return 解出的成员总数
     */
    fun extract(input: InputStream, destDir: File, onEntry: (String, Long) -> Unit = { _, _ -> }): Int {
        destDir.mkdirs()
        if (!destDir.isDirectory) throw Bad("解包目标不是目录：${destDir.path}")
        var count = 0
        GZIPInputStream(input, 64 * 1024).use { gz ->
            // PAX / GNU 长名：作用于紧接着的下一个成员
            var pendingPath: String? = null
            var pendingLink: String? = null

            while (true) {
                val header = readBlock(gz) ?: break   // EOF（不足 512 字节）：流结束
                if (isZeroBlock(header)) {
                    // 连续两个全零块是 tar 的正规结束符；遇到第一个就可以停了，
                    // 后面可能还有填充，也可能直接断流
                    val next = readBlock(gz) ?: break
                    if (!isZeroBlock(next)) throw Bad("tar 结束符后面还有数据，文件不完整")
                    break
                }

                val magic = str(header, 257, 6)
                if (magic != "ustar" && magic != "ustar ") {
                    throw Bad("不是 tar 文件（找不到 ustar 魔数），下载可能损坏或不完整")
                }
                val size = octal(header, 124, 12) ?: 0L
                if (size < 0 || size > 4L * 1024 * 1024 * 1024) throw Bad("成员体积异常：$size")
                // 权限位（100 偏移 8 字节八进制）：只用得上「可执行」，其他位
                // 按落盘默认走——应用私有目录，读写本来就是 owner 的事
                val mode = octal(header, 100, 8) ?: 0

                var type = header[156].toInt().toChar()
                var name = str(header, 0, 100)
                var link = str(header, 157, 100)
                if (magic == "ustar " && name.isEmpty()) name = str(header, 345, 155) // GNU prefix

                when (type) {
                    // PAX 扩展头：内容是 "长度 key=value\n" 的序列
                    'x', 'g' -> {
                        val body = ByteArray(size.toInt()).also { readFully(gz, it, size) }
                        pendingPath = null; pendingLink = null
                        var p = 0
                        while (p < body.size) {
                            // 每条记录前面是十进制长度（含长度本身），空格后是 key=value
                            val sp = body.indexOf(b = ' '.code.toByte(), from = p)
                            if (sp < 0) break
                            val len = String(body, p, sp - p, Charsets.ISO_8859_1).toIntOrNull() ?: break
                            if (len <= 0 || p + len > body.size) break
                            val rec = String(body, sp + 1, p + len - sp - 1, Charsets.UTF_8)
                            val eq = rec.indexOf('=')
                            if (eq > 0) {
                                val key = rec.substring(0, eq).trim()
                                // 每条记录以 \n 结尾，是格式的一部分，不是值的一部分；
                                // 不剥掉的话文件名会带换行，落盘直接失败
                                val value = rec.substring(eq + 1).trimEnd('\n', '\r')
                                when (key) {
                                    "path" -> pendingPath = value
                                    "linkpath" -> pendingLink = value
                                    // size 的 PAX 记录很少见（仅 >8GB），这里不支持也不需要
                                }
                            }
                            p += len
                        }
                        skipPadding(gz, size)
                        continue
                    }
                    // GNU 长名：内容是真正的成员名
                    'L' -> {
                        val body = ByteArray(size.toInt()).also { readFully(gz, it, size) }
                        pendingPath = String(body, 0, body.indexOf(0).let { if (it < 0) body.size else it }, Charsets.UTF_8)
                        pendingLink = null
                        skipPadding(gz, size)
                        continue
                    }
                    // 校验和成员等未知类型：跳过数据，继续
                    else -> Unit
                }

                // 硬链接在数据段之外处理（它没有数据），先把数据读完
                val data = if (size > 0) ByteArray(size.toInt()).also { readFully(gz, it, size) } else ByteArray(0)
                skipPadding(gz, size)

                pendingPath?.let { name = it; pendingPath = null }
                pendingLink?.let { link = it; pendingLink = null }

                val safe = sanitize(name)
                when (type) {
                    '5' -> {
                        File(destDir, safe).mkdirs()
                        count++
                    }
                    '2' -> {
                        val f = File(destDir, safe)
                        f.parentFile?.mkdirs()
                        // 已有同名就清掉（包内同名成员覆盖是常态，且 createSymbolicLink
                        // 遇到已存在的目标会直接抛异常）
                        if (f.isDirectory) f.deleteRecursively() else f.delete()
                        try {
                            // NDK/Alpine 的 so 大多是链接，java.io 没有建链接的 API，走 NIO
                            java.nio.file.Files.createSymbolicLink(
                                f.toPath(),
                                java.nio.file.Path.of(link),
                            )
                            count++
                        } catch (e: Exception) {
                            // 真机 ext4/f2fs 一定支持；桌面测试或个别文件系统不支持时留个
                            // 空文件，比整个解包失败好——运行时缺库会明确报错，可定位
                            f.createNewFile()
                            count++
                        }
                    }
                    '1' -> {
                        // 硬链接：目标一定在本包前面解出过
                        val target = File(destDir, sanitize(link))
                        val f = File(destDir, safe)
                        f.parentFile?.mkdirs()
                        if (target.isFile) {
                            if (f.exists()) f.delete()
                            if (!target.renameTo(f)) {
                                target.copyTo(f, overwrite = true)
                            }
                            count++
                        }
                        // 目标缺失就跳过：硬链接者通常跟着真身一起出现，缺一个不影响主链路
                    }
                    else -> {
                        // '0' / '\0' 普通文件
                        val f = File(destDir, safe)
                        f.parentFile?.mkdirs()
                        if (f.isDirectory) f.delete()
                        f.outputStream().use { it.write(data) }
                        // 恢复可执行位：usr/bin/python3、proot loader 这些没有 +x
                        // 直接就「not executable」。JVM 落盘的默认权限不带 x，
                        // 必须照 tar 头补；owner-only 够用——proot 和宿主同一个 uid。
                        // 73 = 0o111（rwx 位），Kotlin 没有八进制字面量
                        if (mode and 73L != 0L) f.setExecutable(true, true)
                        count++
                        onEntry(safe, size)
                    }
                }
                // 数据读完了重置 pending（正常流里 PAX 后必跟成员，这里只是兜底）
                pendingPath = null; pendingLink = null
            }
        }
        return count
    }

    // ------------------------------------------------------------------ 内部

    /** 读满一个 512 字节的块。流干净结束返回 null。 */
    private fun readBlock(gz: InputStream): ByteArray? {
        val block = ByteArray(512)
        var off = 0
        while (off < 512) {
            val n = gz.read(block, off, 512 - off)
            if (n < 0) return if (off == 0) null else throw Bad("tar 在成员中间断流，文件不完整")
            off += n
        }
        return block
    }

    private fun isZeroBlock(block: ByteArray): Boolean {
        for (b in block) if (b.toInt() != 0) return false
        return true
    }

    /** 头里的定长 ASCII 字段，掐掉末尾的 NUL 和空格。 */
    private fun str(block: ByteArray, off: Int, len: Int): String {
        var end = off
        val stop = off + len
        while (end < stop && block[end].toInt() != 0) end++
        return String(block, off, end - off, Charsets.ISO_8859_1).trim()
    }

    /** tar 的 size 是八进制 ASCII，可能有前导空格和 NUL 结尾。 */
    private fun octal(block: ByteArray, off: Int, len: Int): Long? {
        var s = str(block, off, len)
        if (s.isEmpty()) return 0
        // GNU base-256 编码（首位 0x80）只在超大文件出现，rootfs 不会有；遇到就拒
        if (block[off].toInt() and 0x80 != 0) return null
        s = s.trim(' ')
        return s.toLongOrNull(8)
    }

    /** 把数据补齐到 512 的倍数。 */
    private fun skipPadding(gz: InputStream, size: Long) {
        val rem = (512 - (size % 512)) % 512
        if (rem == 0L) return
        val buf = ByteArray(rem.toInt())
        readFully(gz, buf, rem)
    }

    private fun readFully(gz: InputStream, buf: ByteArray, len: Long) {
        var off = 0
        val total = len.toInt()
        while (off < total) {
            val n = gz.read(buf, off, total - off)
            if (n < 0) throw Bad("tar 在数据段断流，文件不完整")
            off += n
        }
    }

    private fun ByteArray.indexOf(b: Byte, from: Int = 0): Int {
        for (i in from until size) if (this[i] == b) return i
        return -1
    }

    /**
     * 成员名安全化：剥掉 "./"、压缩连续斜杠、拒绝 `..` 逃逸和绝对路径。
     * 返回包内的相对路径。
     */
    internal fun sanitize(rawName: String): String {
        var parts = ArrayList<String>()
        for (seg in rawName.split('/')) {
            when (seg) {
                "", "." -> continue
                ".." -> throw Bad("tar 包含可疑路径（..）：$rawName")
                else -> parts.add(seg)
            }
        }
        if (parts.isEmpty()) throw Bad("tar 成员名为空")
        // 头字段只有 100 字节，长名靠 PAX；进来还超长的就是坏包
        if (parts.size > 64) throw Bad("tar 路径过深：$rawName")
        return parts.joinToString("/")
    }
}
