package org.mozilla.fenix.settings.doh

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/** TXT `ip:port` first, then A on 443. Independent of TRR. */
internal object DohTxtLookup {
    data class Endpoint(val ip: String, val port: Int, val fromTxt: Boolean = true) {
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
        return Endpoint(ip, port, fromTxt = true)
    }

    fun lookup(hosts: List<String>, resolvers: List<String> = DEFAULT_RESOLVERS): List<Endpoint> {
        val found = linkedSetOf<Endpoint>()
        for (host in hosts.map { it.trim().trimEnd('.') }.filter { it.isNotBlank() }) {
            for (resolver in resolvers) {
                val packet = runCatching { query(host, type = 16, resolver) }.getOrNull() ?: continue
                packet.txt.mapNotNull(::parsePayload).forEach { found += it }
                if (found.isNotEmpty()) return found.toList()
                val cname = packet.cname
                if (cname != null && !cname.equals(host, ignoreCase = true)) {
                    val via = runCatching { query(cname, type = 16, resolver) }.getOrNull()
                    via?.txt?.mapNotNull(::parsePayload)?.forEach { found += it }
                    if (found.isNotEmpty()) return found.toList()
                }
            }
            for (resolver in resolvers) {
                val packet = runCatching { query(host, type = 1, resolver) }.getOrNull() ?: continue
                packet.a.forEach { found += Endpoint(it, 443, fromTxt = false) }
                if (found.isNotEmpty()) return found.toList()
            }
        }
        return found.toList()
    }

    private val id = AtomicInteger(1)

    private data class Packet(val txt: List<String>, val a: List<String>, val cname: String?)

    private fun query(name: String, type: Int, resolver: String): Packet {
        val query = encodeQuery(name, type)
        DatagramSocket().use { socket ->
            socket.soTimeout = 3_000
            socket.send(DatagramPacket(query, query.size, InetAddress.getByName(resolver), 53))
            val buf = ByteArray(2048)
            val packet = DatagramPacket(buf, buf.size)
            socket.receive(packet)
            return decode(packet.data, packet.length)
        }
    }

    private fun encodeQuery(name: String, type: Int): ByteArray {
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
        buffer.putShort(type.toShort())
        buffer.putShort(1)
        return buffer.array()
    }

    private fun decode(data: ByteArray, length: Int): Packet {
        val txt = mutableListOf<String>()
        val a = mutableListOf<String>()
        var cname: String? = null
        if (length < 12) return Packet(txt, a, null)
        val buffer = ByteBuffer.wrap(data, 0, length)
        buffer.position(4)
        val qd = buffer.short.toInt() and 0xffff
        val an = buffer.short.toInt() and 0xffff
        buffer.position(12)
        repeat(qd) { skipName(buffer); buffer.position(buffer.position() + 4) }
        repeat(an) {
            skipName(buffer)
            if (buffer.remaining() < 10) return Packet(txt, a, cname)
            val type = buffer.short.toInt() and 0xffff
            buffer.short
            buffer.int
            val rdlen = buffer.short.toInt() and 0xffff
            if (buffer.remaining() < rdlen) return Packet(txt, a, cname)
            val end = buffer.position() + rdlen
            when (type) {
                16 -> {
                    while (buffer.position() < end) {
                        val n = buffer.get().toInt() and 0xff
                        if (buffer.position() + n > end) break
                        val bytes = ByteArray(n)
                        buffer.get(bytes)
                        txt += String(bytes, Charsets.UTF_8)
                    }
                }
                1 -> if (rdlen == 4) {
                    val b = ByteArray(4)
                    buffer.get(b)
                    a += "${b[0].toInt() and 0xff}.${b[1].toInt() and 0xff}.${b[2].toInt() and 0xff}.${b[3].toInt() and 0xff}"
                } else {
                    buffer.position(end)
                }
                5 -> {
                    if (cname == null) cname = readName(data, length, buffer.position())
                    buffer.position(end)
                }
                else -> buffer.position(end)
            }
        }
        return Packet(txt, a, cname)
    }

    private fun readName(data: ByteArray, length: Int, start: Int): String? {
        val labels = mutableListOf<String>()
        var i = start
        var jumps = 0
        while (i < length && jumps < 10) {
            val len = data[i].toInt() and 0xff
            when {
                len == 0 -> break
                len and 0xc0 == 0xc0 -> {
                    if (i + 1 >= length) break
                    i = ((len and 0x3f) shl 8) or (data[i + 1].toInt() and 0xff)
                    jumps++
                    continue
                }
                else -> {
                    if (i + 1 + len > length) break
                    labels += String(data, i + 1, len, Charsets.US_ASCII)
                    i += 1 + len
                }
            }
        }
        return labels.takeIf { it.isNotEmpty() }?.joinToString(".")
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

    val DEFAULT_RESOLVERS = listOf("223.5.5.5", "119.29.29.29")
}
