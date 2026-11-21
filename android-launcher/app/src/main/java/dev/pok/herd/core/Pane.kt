package dev.pok.herd.core

import android.graphics.Typeface
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import kotlin.concurrent.thread

/**
 * herdr's states. `done` = finished and not yet seen (cleared when focused); `unknown` = something
 * is running but has been silent too long to classify. [rank] orders the roll-up: the worst state
 * of a tab/workspace is what its badge shows.
 */
enum class PaneState(val label: String, val color: Int, val rank: Int) {
    IDLE("idle", Palette.IDLE, 0),
    UNKNOWN("unknown", Palette.DIM, 1),
    WORKING("working", Palette.WORKING, 2),
    DONE("done", Palette.DONE, 3),
    BLOCKED("blocked", Palette.BLOCKED, 4);

    companion object {
        fun parse(s: String) = entries.firstOrNull { it.label == s }
    }
}

/** Where command output goes: a pane's scrollback, or a buffer returned over the API. */
interface Out {
    fun print(text: String, color: Int? = null)
}

/**
 * One pane = one long-lived `/system/bin/sh` owned by the server. Commands are written to the
 * shell's stdin wrapped with a sentinel so we know when each finishes, its exit code and the new
 * cwd. While a command runs, typed lines go straight to its stdin (that's how you answer a
 * blocked agent).
 */
class Pane(
    val id: Int,
    /** herdr-style public id, e.g. `w1:p2`. */
    val ref: String,
    var name: String?,
    var cwd: String,
    private val herd: Herd,
) : Out {
    val buffer = SpannableStringBuilder()
    val history = ArrayList<String>()

    var running = false; private set
    var command: String? = null; private set
    var lastExit: Int? = null; private set
    var lastOutputAt = 0L; private set
    var state = PaneState.IDLE
    /** Finished while unfocused (herdr's "done" marker). */
    var unseen = false

    private val ansi = AnsiParser()
    private var proc: Process? = null
    private var stdin: OutputStream? = null
    private var shellPid = -1
    private var generation = 0
    private val pending = StringBuilder()
    private var lastInterruptAt = 0L

    /** The agent herdr would recognise in this pane (by its foreground command), if any. */
    val agentKind: String?
        get() {
            if (!running) return null
            val words = command?.trim()?.split(Regex("\\s+")) ?: return null
            return words.take(3).map { it.substringAfterLast('/') }.firstOrNull { it in AGENT_KINDS }
        }

    val title: String
        get() = name
            ?: agentKind
            ?: command?.takeIf { running }?.trim()?.substringBefore(' ')?.substringAfterLast('/')
            ?: herd.shortPath(cwd).substringAfterLast('/').ifEmpty { "~" }

    // ---- process lifecycle -------------------------------------------------------------------

    private fun ensureShell() {
        if (proc != null) return
        val gen = ++generation
        val dir = File(cwd).takeIf { it.isDirectory } ?: herd.home.also { cwd = it.path }
        val pb = ProcessBuilder("/system/bin/sh").redirectErrorStream(true).directory(dir)
        pb.environment().apply {
            put("HOME", herd.home.path)
            put("TERM", "dumb")
            put("PAGER", "cat")
            putAll(herd.paneEnv(this@Pane))
        }
        val p = try { pb.start() } catch (e: IOException) {
            print("failed to start shell: ${e.message}", Palette.BLOCKED); return
        }
        proc = p
        stdin = p.outputStream
        thread(name = "herd-pane-$id", isDaemon = true) {
            val reader = InputStreamReader(p.inputStream, Charsets.UTF_8)
            val buf = CharArray(8192)
            try {
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    val s = String(buf, 0, n)
                    herd.main.post { if (gen == generation) onOutput(s) }
                }
            } catch (_: IOException) {
            }
            herd.main.post { if (gen == generation) onShellExit() }
        }
        write("printf '\\036PID %s\\n' \"$$\"\n")
        // `herdr` inside the pane talks to the server through the API shim.
        write("herdr() { sh \"\$HERDR_SHIM\" \"\$@\"; }\n")
    }

    private fun write(s: String) {
        val out = stdin ?: return
        herd.io.execute {
            try { out.write(s.toByteArray()); out.flush() } catch (_: IOException) {}
        }
    }

    /** Runs [cmd] in this pane's shell. */
    fun exec(cmd: String) {
        ensureShell()
        if (proc == null) return
        running = true
        command = cmd
        unseen = false
        lastOutputAt = SystemClock.uptimeMillis()
        // The brace group is parsed in full before it runs, so the sentinel can't be eaten by a
        // program that reads stdin; the newline before `}` keeps a trailing `# comment` harmless.
        write("{ $cmd\n} ; printf '\\036DONE %s %s\\n' \"\$?\" \"\$PWD\"\n")
        herd.onPaneChanged(this)
    }

    /** Sends a raw line to whatever is running (no TTY, so we echo it ourselves). */
    fun sendInput(line: String) {
        append(line + "\n", Palette.ACCENT)
        lastOutputAt = SystemClock.uptimeMillis()
        write(line + "\n")
        herd.onPaneChanged(this)
    }

    private val typed = StringBuilder()

    /** `send-text`: raw text to the running program, or onto the pending command line if idle. */
    fun type(text: String) {
        if (running) {
            append(text, Palette.ACCENT)
            lastOutputAt = SystemClock.uptimeMillis()
            write(text)
            herd.onPaneChanged(this)
        } else typed.append(text)
    }

    /** The enter key: ends the running program's input line, or submits the typed command. */
    fun enter() {
        if (running) { type("\n"); return }
        val line = typed.toString()
        typed.setLength(0)
        herd.submitTo(this, line)
    }

    /** ctrl+c: SIGINT the shell's children. Pressing again within 2 s restarts the shell. */
    fun interrupt() {
        append("^C\n", Palette.DIM)
        if (!running) return
        val now = SystemClock.uptimeMillis()
        val pid = shellPid
        if (pid <= 0 || now - lastInterruptAt < 2000) { restart(); return }
        lastInterruptAt = now
        herd.io.execute {
            try {
                Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", "pkill -INT -P $pid")).waitFor()
            } catch (_: Exception) {}
        }
    }

    fun restart() {
        kill()
        print("[shell restarted]", Palette.DIM)
        herd.onPaneChanged(this)
    }

    fun kill() {
        generation++
        val p = proc
        proc = null; stdin = null; shellPid = -1
        running = false; command = null
        pending.setLength(0)
        if (p != null) herd.io.execute {
            try { p.outputStream.close() } catch (_: IOException) {}
            p.destroy()
        }
    }

    // ---- output ------------------------------------------------------------------------------

    private fun onOutput(chunk: String) {
        lastOutputAt = SystemClock.uptimeMillis()
        pending.append(chunk)
        while (true) {
            val s = pending.indexOf(MARK)
            if (s < 0) { emit(pending.toString()); pending.setLength(0); break }
            if (s > 0) { emit(pending.substring(0, s)); pending.delete(0, s) }
            val e = pending.indexOf("\n")
            if (e < 0) break // marker incomplete, wait for the rest
            val payload = pending.substring(1, e)
            pending.delete(0, e + 1)
            onMarker(payload)
        }
        herd.onPaneOutput(this)
    }

    private fun onMarker(payload: String) {
        val parts = payload.split(' ', limit = 3)
        when (parts[0]) {
            "PID" -> shellPid = parts.getOrNull(1)?.toIntOrNull() ?: -1
            "DONE" -> {
                val code = parts.getOrNull(1)?.toIntOrNull() ?: 0
                parts.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { cwd = it }
                running = false
                lastExit = code
                if (code != 0) print("[exit $code]", Palette.BLOCKED)
                herd.onCommandFinished(this)
            }
        }
    }

    private fun onShellExit() {
        proc = null; stdin = null; shellPid = -1
        pending.setLength(0)
        val wasRunning = running
        running = false; command = null
        print("[shell exited]", Palette.DIM)
        if (wasRunning) herd.onCommandFinished(this) else herd.onPaneChanged(this)
    }

    private fun emit(s: String) {
        if (s.isEmpty()) return
        ansi.feed(s, buffer)
        trim()
    }

    private fun trim() {
        if (buffer.length <= MAX_CHARS + MAX_CHARS / 5) return
        var cut = buffer.length - MAX_CHARS
        while (cut < buffer.length && buffer[cut - 1] != '\n') cut++
        buffer.delete(0, cut)
    }

    /** Appends text with an optional colour (used for prompts and system messages). */
    fun append(text: String, color: Int? = null, bold: Boolean = false) {
        val start = buffer.length
        buffer.append(text)
        val end = buffer.length
        color?.let { buffer.setSpan(ForegroundColorSpan(it), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        if (bold) buffer.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        trim()
        herd.onPaneOutput(this)
    }

    /** Prints a full line, starting on a fresh line if needed. */
    override fun print(text: String, color: Int?) {
        if (buffer.isNotEmpty() && buffer[buffer.length - 1] != '\n') append("\n")
        append(text + "\n", color)
    }

    fun echoPrompt(cmd: String) {
        if (buffer.isNotEmpty() && buffer[buffer.length - 1] != '\n') append("\n")
        append(herd.shortPath(cwd), Palette.CWD)
        append(" ❯ ", Palette.ACCENT)
        append(cmd + "\n", Palette.FG, bold = true)
    }

    fun clear() {
        buffer.clear()
        herd.onPaneOutput(this)
    }

    /** Last [lines] lines of plain text, for `herdr pane read`. */
    fun tail(lines: Int): String {
        val text = buffer.toString().trimEnd('\n')
        if (lines <= 0) return text
        var idx = text.length
        repeat(lines) { idx = text.lastIndexOf('\n', idx - 1).also { if (it < 0) return text } }
        return text.substring(idx + 1)
    }

    // ---- state -------------------------------------------------------------------------------

    fun computeState(now: Long): PaneState {
        val quiet = now - lastOutputAt
        return when {
            running && quiet < QUIET_MS -> PaneState.WORKING
            running && looksLikePrompt() -> PaneState.BLOCKED
            running && quiet < SILENT_MS -> PaneState.WORKING
            running -> PaneState.UNKNOWN
            unseen -> PaneState.DONE
            else -> PaneState.IDLE
        }
    }

    /** Heuristic for "the program stopped and is asking something". */
    private fun looksLikePrompt(): Boolean {
        val tail = buffer.subSequence(maxOf(0, buffer.length - 240), buffer.length).toString()
        val line = tail.trimEnd('\n', ' ').substringAfterLast('\n').trimEnd()
        if (line.isEmpty()) return false
        val lower = line.lowercase()
        if (PROMPT_WORDS.any { it in lower }) return true
        return line.last() in PROMPT_ENDINGS
    }

    companion object {
        private const val MARK = '\u001e'
        private const val MAX_CHARS = 100_000
        private const val QUIET_MS = 1200L
        private const val SILENT_MS = 15_000L
        /** Agent kinds herdr supports (from its `agent start --kind` list), plus aider. */
        val AGENT_KINDS = setOf(
            "pi", "claude", "codex", "gemini", "cursor", "cursor-agent", "devin", "agy", "cline", "omp", "mastracode",
            "opencode", "copilot", "kimi", "kiro", "droid", "amp", "grok", "hermes", "kilo", "qodercli", "qwen",
            "letta", "maki", "muse", "aider",
        )
        private val PROMPT_ENDINGS = setOf('?', ':', '>', '›', '❯', ']', ')', '$', '#')
        private val PROMPT_WORDS = listOf("[y/n]", "(y/n)", "[y/n", "y/n]", "yes/no", "press enter", "password", "continue?")
    }
}
