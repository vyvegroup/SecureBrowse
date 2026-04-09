package com.robloxblocker.util

import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Utility for building and parsing IP/UDP packets.
 */
object PacketUtil {

    /**
     * Convert an IPv4 address from int to dotted-decimal string.
     */
    fun intToIpAddress(ip: Int): String {
        return "${ip shr 24 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 8 and 0xFF}.${ip and 0xFF}"
    }

    /**
     * Convert dotted-decimal IPv4 to int.
     */
    fun ipAddressToInt(ip: String): Int {
        val parts = ip.split(".")
        return (parts[0].toInt() shl 24) or
                (parts[1].toInt() shl 16) or
                (parts[2].toInt() shl 8) or
                parts[3].toInt()
    }

    /**
     * Build a minimal IPv4 header for a UDP DNS packet.
     */
    fun buildIpv4Header(payloadSize: Int, destIp: String): ByteArray {
        val totalLength = 20 + 8 + payloadSize  // IP header + UDP header + payload
        val header = ByteArray(20)

        header[0] = 0x45.toByte()  // Version 4, IHL 5
        header[1] = 0x00           // DSCP/ECN
        header[2] = (totalLength shr 8).toByte()
        header[3] = totalLength.toByte()
        header[4] = 0x00           // Identification
        header[5] = 0x00
        header[6] = 0x40.toByte()  // Don't fragment
        header[7] = 0x00
        header[8] = 64             // TTL
        header[9] = 0x11           // Protocol: UDP

        // Source IP (fake - 10.0.0.1)
        val srcIp = ipAddressToInt("10.0.0.1")
        header[12] = (srcIp shr 24).toByte()
        header[13] = (srcIp shr 16).toByte()
        header[14] = (srcIp shr 8).toByte()
        header[15] = srcIp.toByte()

        // Destination IP
        val dstIp = ipAddressToInt(destIp)
        header[16] = (dstIp shr 24).toByte()
        header[17] = (dstIp shr 16).toByte()
        header[18] = (dstIp shr 8).toByte()
        header[19] = dstIp.toByte()

        // Calculate checksum
        val checksum = calculateChecksum(header)
        header[10] = (checksum shr 8).toByte()
        header[11] = checksum.toByte()

        return header
    }

    /**
     * Build a UDP header for DNS response.
     */
    fun buildUdpHeader(payloadSize: Int, srcPort: Int, dstPort: Int): ByteArray {
        val header = ByteArray(8)
        val totalLength = 8 + payloadSize

        header[0] = (srcPort shr 8).toByte()
        header[1] = srcPort.toByte()
        header[2] = (dstPort shr 8).toByte()
        header[3] = dstPort.toByte()
        header[4] = (totalLength shr 8).toByte()
        header[5] = totalLength.toByte()
        header[6] = 0x00  // Checksum (0 = skip)
        header[7] = 0x00

        return header
    }

    /**
     * Calculate IP header checksum for the first `headerLength` bytes.
     * The checksum field itself (bytes 10-11) must be zeroed before calling.
     */
    fun calculateIpChecksum(data: ByteArray, headerLength: Int): Int {
        var sum = 0
        for (i in 0 until headerLength step 2) {
            val high = (data[i].toInt() and 0xFF) shl 8
            val low = if (i + 1 < headerLength) (data[i + 1].toInt() and 0xFF) else 0
            sum += high or low
        }
        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }

    /**
     * Calculate IP header checksum.
     */
    private fun calculateChecksum(header: ByteArray): Int {
        var sum = 0
        for (i in 0 until header.size step 2) {
            sum += ((header[i].toInt() and 0xFF) shl 8) or (header[i + 1].toInt() and 0xFF)
        }
        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }

    /**
     * Get domain from bytes starting at offset.
     */
    fun readDomainName(data: ByteArray, offset: Int): Pair<String, Int> {
        val labels = mutableListOf<String>()
        var current = offset
        var jumped = false
        var jumpOffset = -1

        while (current < data.size) {
            val len = data[current].toInt() and 0xFF
            if (len == 0) break

            if ((len and 0xC0) == 0xC0) {
                if (!jumped) {
                    jumpOffset = current + 2
                }
                current = ((len and 0x3F) shl 8) or (data[current + 1].toInt() and 0xFF)
                jumped = true
                continue
            }

            current++
            if (current + len <= data.size) {
                labels.add(String(data, current, len))
                current += len
            } else {
                break
            }
        }

        val endOffset = if (jumped) jumpOffset else current + 1
        return Pair(labels.joinToString("."), endOffset)
    }
}
