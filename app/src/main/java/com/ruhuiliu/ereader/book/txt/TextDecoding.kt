package com.ruhuiliu.ereader.book.txt

import org.mozilla.universalchardet.UniversalDetector
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Detects and decodes the encoding of plain-text books (UTF-8/16, GB18030, Big5, Shift_JIS, EUC-JP...). */
object TextDecoding {

    fun detectCharset(bytes: ByteArray): Charset {
        // Byte order marks are authoritative.
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return Charsets.UTF_8
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return Charsets.UTF_16LE
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return Charsets.UTF_16BE

        // Strict UTF-8 check first: valid UTF-8 is by far the most common case.
        if (isValid(bytes, Charsets.UTF_8)) return Charsets.UTF_8

        val detector = UniversalDetector(null)
        val limit = minOf(bytes.size, 2 * 1024 * 1024)
        var off = 0
        while (off < limit && !detector.isDone) {
            val n = minOf(64 * 1024, limit - off)
            detector.handleData(bytes, off, n)
            off += n
        }
        detector.dataEnd()
        val detected = detector.detectedCharset?.let { normalizeName(it) }?.let { runCatching { Charset.forName(it) }.getOrNull() }
        if (detected != null && isValid(bytes, detected, lenientTail = true)) return detected

        for (name in listOf("GB18030", "Big5", "windows-31j", "EUC-JP", "EUC-KR")) {
            val cs = runCatching { Charset.forName(name) }.getOrNull() ?: continue
            if (isValid(bytes, cs, lenientTail = true)) return cs
        }
        return detected ?: Charset.forName("GB18030")
    }

    private fun normalizeName(name: String): String = when (name.uppercase()) {
        "GB2312", "GBK", "HZ-GB-2312" -> "GB18030"
        "SHIFT_JIS", "SHIFT-JIS", "SJIS" -> "windows-31j" // superset with NEC/IBM extensions
        "BIG5", "BIG-5" -> "Big5"
        else -> name
    }

    private fun isValid(bytes: ByteArray, cs: Charset, lenientTail: Boolean = false): Boolean {
        val decoder = cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        // Check a bounded prefix; trim a few bytes so a multi-byte char cut at the end doesn't fail.
        val len = minOf(bytes.size, 4 * 1024 * 1024)
        val tail = if (len < bytes.size || lenientTail) minOf(4, len) else 0
        return try {
            decoder.decode(ByteBuffer.wrap(bytes, 0, len - tail))
            true
        } catch (_: CharacterCodingException) {
            false
        }
    }

    fun decode(bytes: ByteArray): String {
        val cs = detectCharset(bytes)
        var text = String(bytes, cs)
        if (text.isNotEmpty() && text[0] == '\uFEFF') text = text.substring(1)
        return text.replace("\r\n", "\n").replace('\r', '\n')
    }
}
