package com.chenyuan.pdftoolbox

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * One file the PC can download. [open] must return a fresh stream on every call,
 * because several downloads may run at the same time.
 */
class TransferFile(val name: String, val open: () -> InputStream)

private const val MAX_HEADER_LINE = 8 * 1024
private const val MAX_UPLOAD_BYTES = 512L * 1024 * 1024

/**
 * A tiny HTTP server for direct phone-to-computer transfer over the local network.
 * Endpoints:
 *   GET  /            HTML page: list of files + upload form
 *   GET  /dl?i=N      download the Nth file
 *   POST /up?name=    upload a file (raw body, size = Content-Length)
 * Every request must carry ?key= matching the shared secret.
 */
class TransferServer(
    private val port: Int,
    private val key: String,
    private val filesProvider: () -> List<TransferFile>,
    private val receivedDir: File,
    private val onUpload: (File) -> Unit
) {
    var running = false
        private set
    var boundPort = port
        private set

    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    /** Binds synchronously, then serves on worker threads. Returns false if the port is taken. */
    fun start(): Boolean {
        if (running) return true
        val ss = ServerSocket()
        return try {
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(port))
            running = true
            boundPort = port
            serverSocket = ss
            pool.execute {
                try {
                    while (running) {
                        val client = ss.accept()
                        pool.execute { runCatching { handle(client) } }
                    }
                } catch (_: Exception) {
                    // socket closed by stop()
                } finally {
                    runCatching { ss.close() }
                }
            }
            true
        } catch (e: Exception) {
            runCatching { ss.close() }
            false
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            s.soTimeout = 15000
            val ins = BufferedInputStream(s.getInputStream())
            val out = s.getOutputStream()

            val requestLine = try {
                ins.readLine()
            } catch (e: Exception) {
                respondText(out, 431, "Bad request")
                return
            } ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val rawUri = parts[1]

            var contentLength = 0L
            try {
                var headerCount = 0
                while (true) {
                    val line = ins.readLine()
                    if (line == null) return
                    if (line.isEmpty()) break
                    if (++headerCount > 100) {
                        respondText(out, 431, "Too many headers")
                        return
                    }
                    val i = line.indexOf(':')
                    if (i > 0 && line.substring(0, i).trim().equals("content-length", ignoreCase = true)) {
                        contentLength = line.substring(i + 1).trim().toLongOrNull() ?: 0L
                    }
                }
            } catch (e: Exception) {
                respondText(out, 431, "Bad request")
                return
            }

            val params = rawUri.substringAfter('?', "")
                .split('&')
                .filter { '=' in it }
                .associate { it.substringBefore('=').urlDecode() to it.substringAfter('=').urlDecode() }
            if (params["key"] != key) {
                respondText(out, 401, "Unauthorized")
                return
            }

            val path = rawUri.substringBefore('?')
            when {
                method == "GET" && path == "/" ->
                    respondText(out, 200, indexHtml(), "text/html; charset=utf-8")

                method == "GET" && path == "/dl" -> {
                    val file = filesProvider().getOrNull(params["i"]?.toIntOrNull() ?: -1)
                    if (file == null) respondText(out, 404, "Not found")
                    else streamDownload(out, file)
                }

                method == "POST" && path == "/up" -> {
                    if (contentLength <= 0) {
                        respondText(out, 400, "Empty upload")
                        return
                    }
                    if (contentLength > MAX_UPLOAD_BYTES) {
                        respondText(out, 413, "File too large (max 512 MB)")
                        return
                    }
                    var safeName = (params["name"] ?: "upload.pdf").sanitizeFileName()
                    if (!safeName.endsWith(".pdf", ignoreCase = true)) safeName += ".pdf"
                    receivedDir.mkdirs()
                    val dest = File(receivedDir, uniqueName(safeName))
                    dest.outputStream().use { o -> ins.copyExactly(o, contentLength) }
                    respondText(out, 200, "OK")
                    onUpload(dest)
                }

                else -> respondText(out, 404, "Not found")
            }
        }
    }

    private fun streamDownload(out: OutputStream, file: TransferFile) {
        val header = "HTTP/1.0 200 OK\r\n" +
            "Content-Type: application/pdf\r\n" +
            "Content-Disposition: attachment; filename=\"${file.name.sanitizeHeaderName()}\"\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        out.write(header.toByteArray(Charsets.US_ASCII))
        file.open().use { src -> src.copyTo(out, 64 * 1024) }
        out.flush()
    }

    private fun respondText(out: OutputStream, code: Int, body: String, type: String = "text/plain; charset=utf-8") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            else -> "Error"
        }
        val head = "HTTP/1.0 $code $reason\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun indexHtml(): String {
        val files = filesProvider()
        val items = if (files.isEmpty()) {
            "<li class=\"empty\">No files yet — add PDFs in the app first.</li>"
        } else {
            files.withIndex().joinToString("") { (index, file) ->
                "<li><a href=\"/dl?key=$key&amp;i=$index\">${esc(file.name)}</a></li>"
            }
        }
        return """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>PDF Toolbox — Transfer</title>
            <style>
            body{font-family:system-ui,sans-serif;max-width:640px;margin:24px auto;padding:0 16px;color:#1b1b1f}
            h1{font-size:22px} h2{font-size:16px;margin-top:28px}
            li{margin:6px 0} a{color:#3b5bdb;text-decoration:none}
            .empty{color:#777;list-style:none;margin-left:-24px}
            #bar{height:8px;background:#e5e5ea;border-radius:4px;margin-top:10px;display:none}
            #fill{height:100%;width:0;background:#3b5bdb;border-radius:4px}
            #st{color:#555;font-size:14px;margin-top:8px}
            input{margin-top:6px}
            </style></head><body>
            <h1>PDF Toolbox — WiFi transfer</h1>
            <h2>Download from phone</h2>
            <ul>$items</ul>
            <h2>Upload to phone</h2>
            <input id="f" type="file" accept=".pdf,application/pdf" multiple>
            <div id="bar"><div id="fill"></div></div><div id="st"></div>
            <script>
            const KEY = "$key";
            const fill = document.getElementById("fill");
            const st = document.getElementById("st");
            document.getElementById("f").onchange = async function () {
              const files = [...this.files];
              document.getElementById("bar").style.display = "block";
              for (let i = 0; i < files.length; i++) {
                await send(files[i], i + 1, files.length);
              }
              setTimeout(() => location.reload(), 600);
            };
            function send(file, idx, total) {
              return new Promise((resolve, reject) => {
                const x = new XMLHttpRequest();
                x.open("POST", "/up?key=" + KEY + "&name=" + encodeURIComponent(file.name));
                x.upload.onprogress = e => {
                  if (e.lengthComputable) {
                    const p = Math.round(100 * (idx - 1 + e.loaded / e.total) / total);
                    fill.style.width = p + "%";
                    st.textContent = "Uploading " + idx + "/" + total + " — " + p + "%";
                  }
                };
                x.onload = () => resolve();
                x.onerror = () => reject(new Error("upload failed"));
                x.send(file);
              });
            }
            </script></body></html>
        """.trimIndent()
    }

    private fun uniqueName(name: String): String {
        var candidate = name
        var n = 1
        while (File(receivedDir, candidate).exists()) {
            val base = name.substringBeforeLast('.', name)
            val ext = if ('.' in name) "." + name.substringAfterLast('.') else ""
            candidate = "$base ($n)$ext"
            n++
        }
        return candidate
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

private fun InputStream.readLine(): String? {
    val sb = StringBuilder()
    while (true) {
        val b = read()
        if (b == -1) return if (sb.isEmpty()) null else sb.toString()
        if (b == '\n'.code) return sb.toString().trimEnd('\r')
        sb.append(b.toChar())
        if (sb.length > MAX_HEADER_LINE) throw IllegalStateException("Header line too long")
    }
}

private fun InputStream.copyExactly(out: OutputStream, count: Long) {
    var remaining = count
    val buf = ByteArray(64 * 1024)
    while (remaining > 0) {
        val n = read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
        if (n == -1) break
        out.write(buf, 0, n)
        remaining -= n
    }
}

private fun String.urlDecode(): String = URLDecoder.decode(this, Charsets.UTF_8.name())

fun String.sanitizeFileName(): String {
    val cleaned = replace(Regex("[/\\\\:*?\"<>|]"), "_")
        .replace(Regex("\\p{Cntrl}"), "")
        .trim()
    return cleaned.ifEmpty { "upload.pdf" }
}

private fun String.sanitizeHeaderName(): String = replace(Regex("[^\\x20-\\x7E]"), "_")
