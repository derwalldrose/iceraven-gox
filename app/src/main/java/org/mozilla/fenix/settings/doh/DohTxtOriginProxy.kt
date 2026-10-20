package org.mozilla.fenix.settings.doh

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Local HTTP CONNECT proxy. If DoH (same overlay) answers a name with a jp/us
 * preferred A record, splice to the TXT ip:port instead of :443. Google and
 * other non-rewritten names stay on their real IPs.
 */
internal object DohTxtOriginProxy {
    @Volatile var listenPort: Int = 0
        private set

    @Volatile private var endpoint: DohTxtLookup.Endpoint? = null
    @Volatile private var dohHost: String = ""
    @Volatile private var dohPath: String = "/ice"
    @Volatile private var preferredIps: Set<String> = emptySet()

    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()
    private val cache = ConcurrentHashMap<String, Pair<Long, Boolean>>()
    private var server: ServerSocket? = null

    fun update(
        endpoint: DohTxtLookup.Endpoint,
        dohUriHost: String,
        dohUriPath: String,
        preferred: Set<String>,
    ) {
        this.endpoint = endpoint
        this.dohHost = dohUriHost
        this.dohPath = if (dohUriPath.isBlank()) "/ice" else dohUriPath
        this.preferredIps = preferred
        cache.clear()
    }

    fun start() {
        if (running.getAndSet(true)) return
        val sock = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = sock
        listenPort = sock.localPort
        pool.execute {
            while (running.get()) {
                try {
                    val client = sock.accept()
                    pool.execute { handle(client) }
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        }
        Log.i(TAG, "origin proxy 127.0.0.1:$listenPort")
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        listenPort = 0
        cache.clear()
    }

    private fun handle(client: Socket) {
        client.soTimeout = 20_000
        try {
            val input = BufferedInputStream(client.getInputStream())
            val header = readHeaders(input) ?: return
            val line = header.line
            if (!line.startsWith("CONNECT ", ignoreCase = true)) {
                client.getOutputStream().write("HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            val target = line.substringAfter(' ').substringBefore(' ')
            val host = target.substringBeforeLast(':').trim('[', ']')
            val port = target.substringAfterLast(':').toIntOrNull() ?: 443
            val dest = route(host, port)
            val remote = Socket()
            remote.soTimeout = 20_000
            remote.connect(InetSocketAddress(dest.first, dest.second), 8_000)
            client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            splice(input, client.getOutputStream(), remote.getInputStream(), remote.getOutputStream(), remote)
        } catch (e: Exception) {
            Log.w(TAG, "connect failed", e)
            runCatching {
                client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".toByteArray())
            }
        } finally {
            runCatching { client.close() }
        }
    }

    private fun route(host: String, port: Int): Pair<String, Int> {
        val ep = endpoint ?: return host to port
        if (host == ep.ip || host.equals(dohHost, ignoreCase = true)) return host to port
        if (host in preferredIps) return ep.ip to ep.port
        if (shouldOverlay(host)) return ep.ip to ep.port
        return host to port
    }

    private fun shouldOverlay(host: String): Boolean {
        val now = System.currentTimeMillis()
        cache[host]?.let { (until, overlay) -> if (until > now) return overlay }
        val ips = runCatching { dohA(host) }.getOrDefault(emptyList())
        val overlay = ips.any { it in preferredIps }
        cache[host] = now + 60_000L to overlay
        return overlay
    }

    private fun dohA(name: String): List<String> {
        val ep = endpoint ?: return emptyList()
        val query = DohTxtLookup.queryMessage(name, 1)
        val raw = Socket()
        raw.connect(InetSocketAddress(ep.ip, ep.port), 5_000)
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val ssl = factory.createSocket(raw, dohHost, ep.port, true) as SSLSocket
        ssl.sslParameters = ssl.sslParameters.apply {
            serverNames = listOf(SNIHostName(dohHost))
        }
        ssl.startHandshake()
        val path = if (dohPath.startsWith("/")) dohPath else "/$dohPath"
        val req = buildString {
            append("POST $path HTTP/1.1\r\n")
            append("Host: $dohHost\r\n")
            append("Content-Type: application/dns-message\r\n")
            append("Accept: application/dns-message\r\n")
            append("Content-Length: ${query.size}\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray()
        ssl.outputStream.write(req)
        ssl.outputStream.write(query)
        ssl.outputStream.flush()
        val body = readHttpBody(ssl.inputStream)
        runCatching { ssl.close() }
        return DohTxtLookup.aRecords(body)
    }

    private data class Head(val line: String)

    private fun readHeaders(input: BufferedInputStream): Head? {
        val buf = ByteArrayOutputStream()
        var prev = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            if (prev == '\r'.code && b == '\n'.code && buf.size() >= 4) {
                val bytes = buf.toByteArray()
                if (bytes.size >= 4 &&
                    bytes[bytes.size - 4] == '\r'.code.toByte() &&
                    bytes[bytes.size - 3] == '\n'.code.toByte()
                ) {
                    val text = bytes.toString(Charsets.ISO_8859_1)
                    val line = text.substringBefore("\r\n")
                    return Head(line)
                }
            }
            prev = b
            if (buf.size() > 16_384) return null
        }
    }

    private fun readHttpBody(input: InputStream): ByteArray {
        val headerBuf = ByteArrayOutputStream()
        var prev = 0
        while (true) {
            val b = input.read()
            if (b < 0) return ByteArray(0)
            headerBuf.write(b)
            if (prev == '\r'.code && b == '\n'.code && headerBuf.size() >= 4) {
                val bytes = headerBuf.toByteArray()
                if (bytes.size >= 4 &&
                    bytes[bytes.size - 4] == '\r'.code.toByte() &&
                    bytes[bytes.size - 3] == '\n'.code.toByte()
                ) {
                    break
                }
            }
            prev = b
        }
        val headers = headerBuf.toString("ISO-8859-1")
        val chunked = headers.contains("Transfer-Encoding: chunked", ignoreCase = true)
        if (chunked) return decodeChunked(input)
        val len = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE)
            .find(headers)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val body = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(body, off, len - off)
            if (n < 0) break
            off += n
        }
        return body.copyOf(off)
    }

    private fun decodeChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: break
            val size = sizeLine.trim().substringBefore(';').toIntOrNull(16) ?: break
            if (size == 0) break
            val chunk = ByteArray(size)
            var off = 0
            while (off < size) {
                val n = input.read(chunk, off, size - off)
                if (n < 0) break
                off += n
            }
            out.write(chunk, 0, off)
            input.read(); input.read()
        }
        return out.toByteArray()
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
        }
        return buf.toString("ISO-8859-1")
    }

    private fun splice(
        cIn: InputStream,
        cOut: OutputStream,
        rIn: InputStream,
        rOut: OutputStream,
        remote: Socket,
    ) {
        pool.execute {
            runCatching { cIn.copyTo(rOut); rOut.flush() }
            runCatching { remote.shutdownOutput() }
        }
        runCatching { rIn.copyTo(cOut); cOut.flush() }
        runCatching { remote.close() }
    }

    private const val TAG = "DohTxtProxy"
}
