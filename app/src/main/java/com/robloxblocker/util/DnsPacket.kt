package com.robloxblocker.util

/**
 * Minimal DNS packet parser for intercepting and analyzing DNS queries.
 */
class DnsPacket(data: ByteArray) {

    private val bytes: ByteArray = data

    /**
     * Extract the queried domain name from a DNS query packet.
     */
    fun getQueryDomain(): String? {
        try {
            if (bytes.size < 12) return null

            val headerSize = 12
            var offset = headerSize

            // Skip the question section(s) - there's usually 1
            val qdCount = ((bytes[4].toInt() and 0xFF) shl 8) or
                    (bytes[5].toInt() and 0xFF)

            if (qdCount == 0) return null

            // Read domain name (label format: length + label bytes)
            val domainParts = mutableListOf<String>()
            var labelLength: Int

            while (offset < bytes.size) {
                labelLength = bytes[offset].toInt() and 0xFF

                if (labelLength == 0) {
                    break  // End of domain name
                }

                // Check for pointer (compression) - not typical in queries but handle it
                if ((labelLength and 0xC0) == 0xC0) {
                    // DNS compression pointer - extract from offset
                    if (offset + 1 < bytes.size) {
                        val pointerOffset = ((labelLength and 0x3F) shl 8) or
                                (bytes[offset + 1].toInt() and 0xFF)
                        val pointedDomain = readDomainFromPointer(pointerOffset)
                        if (pointedDomain.isNotEmpty()) {
                            domainParts.add(pointedDomain)
                        }
                    }
                    break
                }

                offset++
                if (offset + labelLength <= bytes.size) {
                    val label = String(bytes, offset, labelLength)
                    domainParts.add(label)
                    offset += labelLength
                } else {
                    break
                }
            }

            return if (domainParts.isNotEmpty()) domainParts.joinToString(".") else null
        } catch (e: Exception) {
            return null
        }
    }

    private fun readDomainFromPointer(offset: Int): String {
        if (offset >= bytes.size) return ""

        val domainParts = mutableListOf<String>()
        var currentOffset = offset

        while (currentOffset < bytes.size) {
            val length = bytes[currentOffset].toInt() and 0xFF
            if (length == 0) break

            if ((length and 0xC0) == 0xC0) {
                // Another pointer - follow it (prevent infinite loop)
                val nextPointer = ((length and 0x3F) shl 8) or
                        (bytes[currentOffset + 1].toInt() and 0xFF)
                if (nextPointer >= bytes.size) break
                currentOffset = nextPointer
                continue
            }

            currentOffset++
            if (currentOffset + length <= bytes.size) {
                domainParts.add(String(bytes, currentOffset, length))
                currentOffset += length
            } else {
                break
            }
        }

        return domainParts.joinToString(".")
    }

    /**
     * Get the query type (A=1, AAAA=28, etc.)
     */
    fun getQueryType(): Int {
        if (bytes.size < 14) return 0
        return ((bytes[bytes.size - 4].toInt() and 0xFF) shl 8) or
                (bytes[bytes.size - 3].toInt() and 0xFF)
    }

    /**
     * Get the transaction ID
     */
    fun getTransactionId(): Int {
        return ((bytes[0].toInt() and 0xFF) shl 8) or
                (bytes[1].toInt() and 0xFF)
    }
}
