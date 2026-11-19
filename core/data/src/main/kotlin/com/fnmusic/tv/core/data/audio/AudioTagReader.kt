package com.fnmusic.tv.core.data.audio

/**
 * 从音频文件头部字节解析碟号 / 曲目号。
 *
 * 飞牛服务对没有写这套标签（或解析不出）的文件会返回 `discNo` / `trackNo` = null，
 * 专辑页就无从按碟分组。这里在客户端自己读一遍标签兜底：
 *  - MP3（ID3v2.2/2.3/2.4）：TPOS / TRCK
 *  - FLAC（Vorbis Comment）：DISCNUMBER / TRACKNUMBER
 * 只读文件头部若干字节（配合 HTTP Range），不下载整首歌。
 */
object AudioTagReader {

    private const val CACHE_MAX = 256

    /** 解析结果按曲目 guid 缓存（同一首歌反复进出专辑页不再重复请求），简单 LRU */
    private val cache = object : LinkedHashMap<String, Pair<Int, Int>>(CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Int, Int>>?): Boolean =
            size > CACHE_MAX
    }

    @Synchronized
    fun cached(key: String): Pair<Int, Int>? = cache[key]

    @Synchronized
    fun put(key: String, value: Pair<Int, Int>) {
        if (value.first > 0 || value.second > 0) cache[key] = value
    }

    @Synchronized
    fun clear() {
        cache.clear()
    }

    /** 解析头部字节，返回 (碟号, 曲目号)；解析不到的部分为 0 */
    fun parse(bytes: ByteArray): Pair<Int, Int> {
        if (bytes.size < 16) return 0 to 0
        return try {
            when {
                bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte() ->
                    parseId3(bytes)
                bytes[0] == 'f'.code.toByte() && bytes[1] == 'L'.code.toByte() &&
                    bytes[2] == 'a'.code.toByte() && bytes[3] == 'C'.code.toByte() -> parseFlac(bytes)
                else -> 0 to 0
            }
        } catch (_: Throwable) {
            0 to 0
        }
    }

    private fun parseId3(b: ByteArray): Pair<Int, Int> {
        if (b.size < 10) return 0 to 0
        val major = b[3].toInt() and 0xFF
        val flags = b[5].toInt() and 0xFF
        val syncsafe = major == 4
        var pos = 10
        if (flags and 0x40 != 0 && b.size >= 14) { // 扩展头
            val exSize = readInt(b, pos, syncsafe)
            pos += if (syncsafe) exSize else exSize + 4
        }
        var disc = 0
        var track = 0
        while (pos + 10 <= b.size) {
            val id = String(b, pos, 4, Charsets.ISO_8859_1)
            if (id.isBlank() || id[0] == '\u0000') break
            val size = readInt(b, pos + 4, syncsafe)
            if (size <= 0) break
            val bodyStart = pos + 10
            val bodyEnd = minOf(bodyStart + size, b.size)
            if (bodyStart < bodyEnd && (id == "TRCK" || id == "TPOS")) {
                val text = decodeTextFrame(b, bodyStart, bodyEnd)
                val num = leadingNumber(text)
                if (id == "TRCK") track = num else disc = num
            }
            pos = bodyStart + size
        }
        return disc to track
    }

    private fun parseFlac(b: ByteArray): Pair<Int, Int> {
        var pos = 4
        var disc = 0
        var track = 0
        while (pos + 4 <= b.size) {
            val header = b[pos].toInt() and 0xFF
            val isLast = header and 0x80 != 0
            val type = header and 0x7F
            val len = ((b[pos + 1].toInt() and 0xFF) shl 16) or
                ((b[pos + 2].toInt() and 0xFF) shl 8) or (b[pos + 3].toInt() and 0xFF)
            val start = pos + 4
            val end = minOf(start + len, b.size)
            if (type == 4) { // VORBIS_COMMENT
                var p = start
                if (p + 4 > end) return disc to track
                val vendorLen = readLe32(b, p); p += 4
                if (vendorLen < 0 || p + vendorLen > end) return disc to track
                p += vendorLen
                if (p + 4 > end) return disc to track
                val count = readLe32(b, p); p += 4
                var i = 0
                while (i < count && p + 4 <= end) {
                    val l = readLe32(b, p); p += 4
                    if (l < 0 || p + l > end) break
                    val kv = String(b, p, l, Charsets.UTF_8)
                    val eq = kv.indexOf('=')
                    if (eq > 0) {
                        when (kv.substring(0, eq).trim().uppercase()) {
                            "TRACKNUMBER" -> track = leadingNumber(kv.substring(eq + 1))
                            "DISCNUMBER" -> disc = leadingNumber(kv.substring(eq + 1))
                        }
                    }
                    p += l
                    i++
                }
            }
            pos = start + len
            if (isLast) break
        }
        return disc to track
    }

    /** ID3 文本帧：首字节是编码，0=Latin1，1/2=UTF-16，3=UTF-8 */
    private fun decodeTextFrame(b: ByteArray, start: Int, end: Int): String {
        if (start >= end) return ""
        return try {
            when (b[start].toInt() and 0xFF) {
                1 -> {
                    var s = start + 1
                    // 跳过 BOM
                    if (s + 1 < end && ((b[s].toInt() and 0xFF) == 0xFF || (b[s].toInt() and 0xFF) == 0xFE)) {
                        // FF FE = 小端 BOM，FE FF = 大端 BOM
                        val bigEndian = (b[s].toInt() and 0xFF) == 0xFE
                        s += 2
                        String(b, s, end - s, if (bigEndian) Charsets.UTF_16BE else Charsets.UTF_16LE)
                    } else String(b, s, end - s, Charsets.UTF_16BE)
                }
                2 -> String(b, start + 1, end - start - 1, Charsets.UTF_16BE)
                3 -> String(b, start + 1, end - start - 1, Charsets.UTF_8)
                else -> String(b, start + 1, end - start - 1, Charsets.ISO_8859_1)
            }
        } catch (_: Throwable) { "" }
    }

    private fun readInt(b: ByteArray, offset: Int, syncsafe: Boolean): Int {
        if (offset + 4 > b.size) return 0
        return if (syncsafe) {
            ((b[offset].toInt() and 0x7F) shl 21) or ((b[offset + 1].toInt() and 0x7F) shl 14) or
                ((b[offset + 2].toInt() and 0x7F) shl 7) or (b[offset + 3].toInt() and 0x7F)
        } else {
            ((b[offset].toInt() and 0xFF) shl 24) or ((b[offset + 1].toInt() and 0xFF) shl 16) or
                ((b[offset + 2].toInt() and 0xFF) shl 8) or (b[offset + 3].toInt() and 0xFF)
        }
    }

    private fun readLe32(b: ByteArray, offset: Int): Int {
        if (offset + 4 > b.size) return -1
        return (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8) or
            ((b[offset + 2].toInt() and 0xFF) shl 16) or ((b[offset + 3].toInt() and 0xFF) shl 24)
    }

    /** "3/12" / "03" / "CD1" → 取第一段数字 */
    private fun leadingNumber(s: String): Int {
        val m = Regex("(\\d{1,3})").find(s.trim()) ?: return 0
        return m.value.toIntOrNull() ?: 0
    }
}
