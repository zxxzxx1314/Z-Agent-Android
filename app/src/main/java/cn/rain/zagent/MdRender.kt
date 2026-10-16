package cn.rain.zagent

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

// MdRender: minimal Markdown to Spannable (native, no WebView).
// Block : heading, unordered list, ordered list, quote, hr
// Inline: bold, italic, inline code
// Fenced code blocks are handled by MainActivity.makeCodeBlock and never reach here.
object MdRender {

    private val FLAG = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

    private class Seg(
        val text: String,
        val bold: Boolean,
        val italic: Boolean,
        val code: Boolean
    )

    fun render(src: String): CharSequence {
        val sb = SpannableStringBuilder()
        val lines = src.split("\n")
        for (i in lines.indices) {

            if (i > 0) sb.append("\n")
            val line = lines[i].trimEnd()
            when {
                line.isEmpty() -> {}
                isHr(line) -> sb.append("\u2500".repeat(14))
                line.startsWith("#") -> appendHeading(sb, line)
                line.startsWith(">") -> appendQuote(sb, line)
                isBullet(line) -> appendBullet(sb, line)
                isOrdered(line) -> appendOrdered(sb, line)
                else -> appendInline(sb, line)
            }
        }
        return sb
    }

    private fun isHr(line: String): Boolean {
        val t = line.trim()
        if (t.length < 3) return false
        val c = t[0]
        if (c != '-' && c != '*' && c != '_') return false
        for (ch in t) if (ch != c) return false
        return true
    }

    private fun appendHeading(sb: SpannableStringBuilder, line: String) {
        var level = 0
        while (level < line.length && level < 6 && line[level] == '#') level++
        val text = line.substring(level).trim()
        val start = sb.length
        appendInline(sb, text)
        val end = sb.length
        if (end > start) {
            val size = when (level) {
                1 -> 1.50f
                2 -> 1.32f
                3 -> 1.18f
                else -> 1.08f
            }
            sb.setSpan(RelativeSizeSpan(size), start, end, FLAG)
            sb.setSpan(StyleSpan(Typeface.BOLD), start, end, FLAG)
        }
    }

    private fun appendQuote(sb: SpannableStringBuilder, line: String) {
        val t = line.trimStart()
        val body = if (t.length > 1) t.substring(1).trimStart() else ""
        sb.append("\u2502  ")
        appendInline(sb, body)
    }

    private fun isBullet(line: String): Boolean {
        val t = line.trimStart()
        if (t.length < 2) return false
        val c = t[0]
        return t[1] == ' ' && (c == '-' || c == '+' || c == '*')
    }

    private fun appendBullet(sb: SpannableStringBuilder, line: String) {
        val t = line.trimStart()
        sb.append("\u2022  ")
        appendInline(sb, t.substring(2))
    }

    private fun isOrdered(line: String): Boolean {
        val t = line.trimStart()
        var i = 0
        while (i < t.length && t[i].isDigit()) i++
        return i > 0 && i + 1 < t.length && t[i] == '.' && t[i + 1] == ' '
    }

    private fun appendOrdered(sb: SpannableStringBuilder, line: String) {
        val t = line.trimStart()
        var i = 0
        while (i < t.length && t[i].isDigit()) i++
        sb.append(t.substring(0, i + 1)).append("  ")
        appendInline(sb, t.substring(i + 2))
    }

    private fun appendInline(sb: SpannableStringBuilder, s: String) {
        for (seg in parseInline(s)) {
            val start = sb.length
            sb.append(seg.text)
            val end = sb.length
            if (end <= start) continue
            if (seg.code) {
                sb.setSpan(TypefaceSpan("monospace"), start, end, FLAG)
            } else if (seg.bold && seg.italic) {
                sb.setSpan(StyleSpan(Typeface.BOLD or Typeface.ITALIC), start, end, FLAG)
            } else if (seg.bold) {
                sb.setSpan(StyleSpan(Typeface.BOLD), start, end, FLAG)
            } else if (seg.italic) {
                sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, FLAG)
            }
        }
    }

    private fun parseInline(s: String): List<Seg> {
        val out = ArrayList<Seg>()
        val buf = StringBuilder()
        var bold = false
        var italic = false
        var code = false
        var i = 0
        while (i < s.length) {
            if (code) {
                if (s[i] == '`') { addSeg(out, buf, bold, italic, true); code = false }
                else buf.append(s[i])
                i++
                continue
            }
            if (s[i] == '`') { addSeg(out, buf, bold, italic, false); code = true; i++; continue }
            if (s.startsWith("**", i)) { addSeg(out, buf, bold, italic, false); bold = !bold; i += 2; continue }
            if (s[i] == '*') { addSeg(out, buf, bold, italic, false); italic = !italic; i++; continue }
            buf.append(s[i])
            i++
        }
        addSeg(out, buf, bold, italic, code)
        return out
    }

    private fun addSeg(out: MutableList<Seg>, buf: StringBuilder, bold: Boolean, italic: Boolean, code: Boolean) {
        if (buf.isNotEmpty()) {
            out.add(Seg(buf.toString(), bold, italic, code))
            buf.setLength(0)
        }
    }
}
