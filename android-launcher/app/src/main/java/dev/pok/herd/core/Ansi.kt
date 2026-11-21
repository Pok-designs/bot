package dev.pok.herd.core

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan

/** Shared colour palette (tokyo-night-ish). */
object Palette {
    const val BG = 0xFF0D0F12.toInt()
    const val SURFACE = 0xFF15181D.toInt()
    const val SURFACE_FOCUS = 0xFF1D2330.toInt()
    const val DIVIDER = 0xFF262B33.toInt()
    const val FG = 0xFFD6DBE1.toInt()
    const val DIM = 0xFF6B7480.toInt()
    const val ACCENT = 0xFF7AA2F7.toInt()
    const val CWD = 0xFF7DCFFF.toInt()
    const val WORKING = 0xFFE0AF68.toInt()
    const val BLOCKED = 0xFFF7768E.toInt()
    const val DONE = 0xFF9ECE6A.toInt()
    const val IDLE = 0xFF565F89.toInt()

    val ANSI16 = intArrayOf(
        0xFF15161E.toInt(), 0xFFF7768E.toInt(), 0xFF9ECE6A.toInt(), 0xFFE0AF68.toInt(),
        0xFF7AA2F7.toInt(), 0xFFBB9AF7.toInt(), 0xFF7DCFFF.toInt(), 0xFFA9B1D6.toInt(),
        0xFF414868.toInt(), 0xFFFF899D.toInt(), 0xFFB9F27C.toInt(), 0xFFFFC777.toInt(),
        0xFF8DB0FF.toInt(), 0xFFC7A9FF.toInt(), 0xFFA4DAFF.toInt(), 0xFFC0CAF5.toInt(),
    )

    fun xterm256(n: Int): Int = when {
        n < 16 -> ANSI16[n]
        n < 232 -> {
            val i = n - 16
            val steps = intArrayOf(0, 95, 135, 175, 215, 255)
            rgb(steps[i / 36], steps[(i / 6) % 6], steps[i % 6])
        }
        else -> (8 + (n - 232) * 10).let { rgb(it, it, it) }
    }

    fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or ((r and 255) shl 16) or ((g and 255) shl 8) or (b and 255)
}

/**
 * Streaming ANSI decoder. Panes have no PTY, so this only needs to cope with what line-oriented
 * programs print: SGR colours/bold are rendered, carriage-return redraws (progress bars) overwrite
 * the current line, and every other escape sequence is dropped.
 */
class AnsiParser {
    private var fg: Int? = null
    private var bold = false
    private var esc: StringBuilder? = null
    private var pendingCr = false

    fun feed(text: String, out: SpannableStringBuilder) {
        val run = StringBuilder()

        fun flush() {
            if (run.isEmpty()) return
            val start = out.length
            out.append(run)
            val end = out.length
            fg?.let { out.setSpan(ForegroundColorSpan(it), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            if (bold) out.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            run.setLength(0)
        }

        for (c in text) {
            val e = esc
            if (e != null) {
                e.append(c)
                when {
                    e.length == 1 -> if (c != '[' && c != ']') esc = null // two-byte sequence, ignore
                    e[0] == '[' -> if (c in '@'..'~') {
                        if (c == 'm') { flush(); sgr(e.substring(1, e.length - 1)) }
                        esc = null
                    }
                    else -> if (c == '\u0007' || (c == '\\' && e.length >= 2 && e[e.length - 2] == '\u001b')) esc = null
                }
                if (esc != null && e.length > 128) esc = null
                continue
            }
            if (pendingCr) {
                pendingCr = false
                if (c != '\n') {
                    // Bare CR: the program wants to redraw the current line.
                    flush()
                    val lineStart = out.lastIndexOf('\n') + 1
                    if (lineStart < out.length) out.delete(lineStart, out.length)
                }
            }
            when (c) {
                '\u001b' -> esc = StringBuilder()
                '\r' -> pendingCr = true
                '\b' -> if (run.isNotEmpty()) run.setLength(run.length - 1)
                else if (out.isNotEmpty() && out[out.length - 1] != '\n') out.delete(out.length - 1, out.length)
                '\n', '\t' -> run.append(c)
                else -> if (c >= ' ') run.append(c)
            }
        }
        flush()
    }

    private fun sgr(params: String) {
        val p = if (params.isEmpty()) listOf(0) else params.split(';').map { it.toIntOrNull() ?: 0 }
        var i = 0
        while (i < p.size) {
            when (val n = p[i]) {
                0 -> { fg = null; bold = false }
                1 -> bold = true
                22 -> bold = false
                in 30..37 -> fg = Palette.ANSI16[n - 30]
                in 90..97 -> fg = Palette.ANSI16[n - 90 + 8]
                39 -> fg = null
                38 -> {
                    if (p.getOrNull(i + 1) == 5 && i + 2 < p.size) { fg = Palette.xterm256(p[i + 2]); i += 2 }
                    else if (p.getOrNull(i + 1) == 2 && i + 4 < p.size) { fg = Palette.rgb(p[i + 2], p[i + 3], p[i + 4]); i += 4 }
                }
                48 -> i += if (p.getOrNull(i + 1) == 5) 2 else if (p.getOrNull(i + 1) == 2) 4 else 0
            }
            i++
        }
    }
}

private fun CharSequence.lastIndexOf(ch: Char): Int {
    for (i in length - 1 downTo 0) if (this[i] == ch) return i
    return -1
}
