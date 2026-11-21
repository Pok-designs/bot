package dev.pok.herd.core

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * herdr is agent-native: programs inside a pane drive it through the CLI, which talks to the
 * server's socket API. Here that's a loopback TCP listener plus a shim script; every pane's shell
 * gets a `herdr` function that runs it (scripts use `sh "$HERDR_SHIM" …`), so a script or agent in one pane can split panes, run commands, read output and
 * wait on other panes exactly like it would with herdr.
 *
 * Loopback is reachable by other apps on the device, so every request must carry a random token
 * that only exists in our panes' environment.
 *
 * Wire format (client → server): token \n caller-pane-id \n argc \n then argc NUL-terminated
 * UTF-8 arguments. The server replies with the command's output and closes the connection.
 */
class HerdApi(private val herd: Herd) {
    /** Not executable directly (Android forbids exec from app data); run as `sh $HERDR_SHIM …`. */
    val shim = File(herd.home, ".herd/herdr.sh")
    private val token = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    @Volatile private var port = 0

    fun env(): Map<String, String> =
        if (port == 0) emptyMap() else mapOf("HERD_API_PORT" to port.toString(), "HERD_API_TOKEN" to token)

    fun start() {
        writeShim()
        val server = try {
            ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            return
        }
        port = server.localPort
        thread(name = "herd-api", isDaemon = true) {
            while (true) {
                val s = try { server.accept() } catch (_: Exception) { break }
                herd.io.execute { handle(s) }
            }
        }
    }

    private fun handle(s: Socket) {
        try {
            s.soTimeout = 10_000
            val input = BufferedInputStream(s.getInputStream())
            val tok = readLine(input)
            if (!MessageDigest.isEqual(tok.toByteArray(), token.toByteArray())) { s.close(); return }
            val caller = readLine(input)
            val argc = readLine(input).toIntOrNull()?.coerceIn(0, 256) ?: 0
            val args = List(argc) { readUntil(input, 0) }
            s.soTimeout = 0 // `agent wait` may legitimately take a long time

            val out = StringBuilder()
            val sink = object : Out {
                override fun print(text: String, color: Int?) { out.append(text).append('\n') }
            }
            val finished = java.util.concurrent.CountDownLatch(1)
            herd.main.post {
                val pane = herd.findPane(caller) ?: herd.focused()
                herd.commands.herdr(pane, args, sink, json = true) { finished.countDown() }
            }
            finished.await()
            s.getOutputStream().apply { write(out.toString().toByteArray()); flush() }
        } catch (_: Exception) {
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }

    private fun readLine(i: InputStream) = readUntil(i, '\n'.code)

    private fun readUntil(i: InputStream, end: Int): String {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = i.read()
            if (c < 0 || c == end) break
            if (b.size() > 1_000_000) break
            b.write(c)
        }
        return b.toString("UTF-8")
    }

    private fun writeShim() {
        shim.parentFile?.mkdirs()
        shim.writeText(SHIM)
    }

    companion object {
        private val SHIM = """
            # herd: forwards `herdr …` from a pane to the launcher's local API.
            if [ -z "${'$'}HERD_API_PORT" ]; then
              echo "herdr: not running inside a herd pane" >&2
              exit 1
            fi
            {
              printf '%s\n%s\n%s\n' "${'$'}HERD_API_TOKEN" "${'$'}HERDR_PANE_ID" "${'$'}#"
              for a in "${'$'}@"; do printf '%s\0' "${'$'}a"; done
            } | nc 127.0.0.1 "${'$'}HERD_API_PORT"
        """.trimIndent() + "\n"
    }
}
