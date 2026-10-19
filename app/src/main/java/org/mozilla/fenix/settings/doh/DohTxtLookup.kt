package org.mozilla.fenix.settings.doh

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/** TXT `ip:port` records used by fleet preferred domains. Independent of TRR. */
internal object DohTxtLookup {
    data class Endpoint(val ip: String, val port: Int) {
        override fun toString() = "$ip:$port"
    }

    fun parsePayload(text: String): Endpoint? {
        val value = text.trim().removeSurrounding("\"")
        val parts = value.split(':')
        if (parts.size != 2) return null
        val ip = parts[0]
        val port = parts[1].toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        val octets = ip.split('.')
        if (octets.size != 4 || octets.any { it.toIntOrNull() !in 0..255 }) return null
        return Endpoint(ip, port)
    }

    fun lookup(hosts: List<String>, resolvers: List<String> = DEFAULT_RESOLVERS): List<Endpoint> {
        val found = linkedSetOf<Endpoint>()
        for (host in hosts.map { it.trim() }.filter { it.isNotBlank() }) {
            for (resolver in resolvers) {
                val records = runCatching { queryTxt(host, resolver) }.getOrDefault(emptyList())
                records.mapNotNull(::parsePayload).forEach { found += it }
                if (found.isNotEmpty()) return found.toList()
            }
        }
        return found.toList()
    }

    private val id = AtomicInteger(1)

    private fun queryTxt(name: String, resolver: String): List<String> {
        val query = encodeQuery(name)
        DatagramSocket().use { socket ->
            socket.soTimeout = 3_000
            socket.send(DatagramPacket(query, query.size, InetAddress.getByName(resolver), 53))
            val buf = ByteArray(2048)
            val packet = DatagramPacket(buf, buf.size)
            socket.receive(packet)
            return decodeTxtAnswers(packet.data, packet.length)
        }
    }

    private fun encodeQuery(name: String): ByteArray {
        val labels = name.trimEnd('.').split('.')
        val size = 12 + labels.sumOf { 1 + it.length } + 1 + 4
        val buffer = ByteBuffer.allocate(size)
        buffer.putShort(id.getAndIncrement().toShort())
        buffer.putShort(0x0100)
        buffer.putShort(1)
        buffer.putShort(0)
        buffer.putShort(0)
        buffer.putShort(0)
        for (label in labels) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            buffer.put(bytes.size.toByte())
            buffer.put(bytes)
        }
        buffer.put(0)
        buffer.putShort(16) // TXT
        buffer.putShort(1)
        return buffer.array()
    }

    private fun decodeTxtAnswers(data: ByteArray, length: Int): List<String> {
        if (length < 12) return emptyList()
        val buffer = ByteBuffer.wrap(data, 0, length)
        buffer.position(4)
        val qd = buffer.short.toInt() and 0xffff
        val an = buffer.short.toInt() and 0xffff
        buffer.position(12)
        repeat(qd) { skipName(buffer); buffer.position(buffer.position() + 4) }
        val records = mutableListOf<String>()
        repeat(an) {
            skipName(buffer)
            if (buffer.remaining() < 10) return records
            val type = buffer.short.toInt() and 0xffff
            buffer.short
            buffer.int
            val rdlen = buffer.short.toInt() and 0xffff
            if (buffer.remaining() < rdlen) return records
            val end = buffer.position() + rdlen
            if (type == 16) {
                while (buffer.position() < end) {
                    val n = buffer.get().toInt() and 0xff
                    if (buffer.position() + n > end) break
                    val bytes = ByteArray(n)
                    buffer.get(bytes)
                    records += String(bytes, Charsets.UTF_8)
                }
            } else {
                buffer.position(end)
            }
        }
        return records
    }

    private fun skipName(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            val len = buffer.get().toInt() and 0xff
            when {
                len == 0 -> return
                len and 0xc0 == 0xc0 -> { buffer.get(); return }
                else -> buffer.position(buffer.position() + len)
            }
        }
    }

    val DEFAULT_RESOLVERS = listOf("223.5.5.5", "8.8.8.8")
}
