package com.robloxblocker.service

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.robloxblocker.util.DnsPacket
import com.robloxblocker.util.PacketUtil
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class BlockerVpnService : VpnService() {

    companion object {
        private const val TAG = "BlockerVPN"
        private const val VPN_MTU = 1500
        private const val DNS_PORT = 53

        // Real DNS servers to forward non-blocked queries
        private val DNS_SERVERS = listOf(
            "8.8.8.8",    // Google Primary
            "8.8.4.4",    // Google Secondary
            "1.1.1.1"     // Cloudflare
        )
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private var blockedCount = 0
    private var totalDnsQueries = 0

    // Roblox domains to block (including subdomains and related)
    private val blockedDomains = setOf(
        "roblox.com",
        "www.roblox.com",
        "web.roblox.com",
        "api.roblox.com",
        "auth.roblox.com",
        "chat.roblox.com",
        "friends.roblox.com",
        "avatar.roblox.com",
        "inventory.roblox.com",
        "trades.roblox.com",
        "catalog.roblox.com",
        "search.roblox.com",
        "develop.roblox.com",
        "create.roblox.com",
        "giftcards.roblox.com",
        "roblox.com.s3.amazonaws.com",
        "robloxlabs.com",
        "roblox.eco",
        "rplus.com",
        "robloxdev.com",
        "robloxwiki.com",
        "rbdn.com",
        "rbxcdn.com",
        "roblox-uploads.s3.amazonaws.com"
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_VPN") {
            stopVpn()
            return START_NOT_STICKY
        }

        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) return

        // Build VPN configuration
        val builder = Builder().apply {
            setSession("SecureBrowse-Blocker")
            setMtu(VPN_MTU)

            // Route all DNS traffic through VPN
            addRoute("0.0.0.0", 0)  // Route ALL traffic
            addDnsServer(DNS_SERVERS[0])
            addDnsServer(DNS_SERVERS[1])

            // Block specific Roblox IP ranges
            // Roblox uses various CDN providers, so DNS blocking is more effective
        }

        try {
            vpnInterface = builder.establish()
            if (vpnInterface != null) {
                isRunning = true
                blockedCount = 0
                totalDnsQueries = 0

                // Show foreground notification
                startForegroundNotification()

                // Start packet processing threads
                thread(name = "VPN-Input") { handleVpnInput() }
                thread(name = "VPN-Output") { handleVpnOutput() }

                Log.i(TAG, "VPN started - blocking ${blockedDomains.size} Roblox domains")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN: ${e.message}", e)
            stopSelf()
        }
    }

    private fun stopVpn() {
        isRunning = false
        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "VPN stopped. Blocked $blockedCount out of $totalDnsQueries DNS queries.")
    }

    private fun startForegroundNotification() {
        val notification = BlockerNotification.createRunningNotification(
            this,
            "SecureBrowse Active",
            "Protecting against restricted domains",
            blockedCount
        )
        startForeground(BlockerNotification.NOTIFICATION_ID, notification)
    }

    private fun handleVpnInput() {
        val vpnFile = vpnInterface?.fileDescriptor ?: return
        val inputStream = FileInputStream(vpnFile)
        val buffer = ByteArray(VPN_MTU)

        while (isRunning) {
            try {
                val length = inputStream.read(buffer)
                if (length > 0) {
                    val packet = buffer.copyOf(length)
                    processPacket(packet)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.w(TAG, "Input error: ${e.message}")
                }
                break
            }
        }
    }

    private fun handleVpnOutput() {
        // Output handled within processPacket via UDP socket
    }

    private fun processPacket(packet: ByteArray) {
        val ipVersion = packet[0].toInt() shr 4 and 0x0F

        when (ipVersion) {
            4 -> processIPv4Packet(packet)
            6 -> processIPv6Packet(packet)
        }
    }

    private fun processIPv4Packet(packet: ByteArray) {
        // Parse IPv4 header
        val protocol = packet[9].toInt() and 0xFF
        val sourceIp = PacketUtil.intToIpAddress(
            ByteBuffer.wrap(packet, 12, 4).int
        )
        val destIp = PacketUtil.intToIpAddress(
            ByteBuffer.wrap(packet, 16, 4).int
        )

        // Check if this is a DNS query (UDP port 53)
        if (protocol == 17) { // UDP
            val srcPort = ByteBuffer.wrap(packet, 20, 2).short.toInt() and 0xFFFF
            val dstPort = ByteBuffer.wrap(packet, 22, 2).short.toInt() and 0xFFFF

            if (dstPort == DNS_PORT) {
                val dnsData = packet.copyOfRange(28, packet.size)
                processDnsQuery(dnsData, sourceIp, srcPort)
                return
            }
        }

        // For non-DNS traffic, allow through (write to VPN interface)
        writePacketToVpn(packet)
    }

    private fun processIPv6Packet(packet: ByteArray) {
        // Parse IPv6 - check for DNS
        if (packet.size > 54) {
            val protocol = packet[6].toInt() and 0xFF
            if (protocol == 17) { // UDP
                val dstPort = ByteBuffer.wrap(packet, 54, 2).short.toInt() and 0xFFFF
                if (dstPort == DNS_PORT) {
                    val dnsData = packet.copyOfRange(58, packet.size)
                    processDnsQuery(dnsData, "::1", 0)
                    return
                }
            }
        }
        writePacketToVpn(packet)
    }

    private fun processDnsQuery(dnsData: ByteArray, clientIp: String, clientPort: Int) {
        totalDnsQueries++

        try {
            val dnsPacket = DnsPacket(dnsData)
            val domainName = dnsPacket.getQueryDomain()

            if (domainName != null && isBlockedDomain(domainName)) {
                blockedCount++
                Log.d(TAG, "BLOCKED DNS query: $domainName")

                // Send NXDOMAIN response back to client
                sendBlockedDnsResponse(dnsData, clientIp, clientPort)

                // Update notification
                BlockerNotification.updateBlockedCount(this, blockedCount)
                return
            }

            // Forward non-blocked DNS query to real DNS server
            forwardDnsQuery(dnsData, clientIp, clientPort)
        } catch (e: Exception) {
            Log.w(TAG, "DNS parse error: ${e.message}")
            // Forward as-is if we can't parse
            forwardDnsQuery(dnsData, clientIp, clientPort)
        }
    }

    private fun isBlockedDomain(domain: String): Boolean {
        val lowerDomain = domain.lowercase().trimEnd('.')
        return blockedDomains.any { blocked ->
            lowerDomain == blocked || lowerDomain.endsWith(".$blocked")
        }
    }

    private fun sendBlockedDnsResponse(originalQuery: ByteArray, clientIp: String, clientPort: Int) {
        // Build NXDOMAIN response (rcode = 3)
        val response = originalQuery.copyOf()

        // Set QR flag to 1 (response) and RCODE to NXDOMAIN (3)
        response[2] = (response[2].toInt() or 0x80).toByte()  // QR = 1
        response[3] = (response[3].toInt() and 0xF0) or 0x03  // RCODE = NXDOMAIN

        // Clear ANCOUNT
        response[6] = 0
        response[7] = 0

        // Send via UDP back through VPN
        sendUdpThroughVpn(response, clientIp, clientPort)
    }

    private fun forwardDnsQuery(dnsData: ByteArray, clientIp: String, clientPort: Int) {
        thread(name = "DNS-Forward") {
            try {
                val socket = DatagramSocket()
                socket.soTimeout = 5000

                // Send to first available DNS server
                val dnsServer = InetAddress.getByName(DNS_SERVERS.first())
                val sendPacket = DatagramPacket(dnsData, dnsData.size, dnsServer, DNS_PORT)
                socket.send(sendPacket)

                // Receive response
                val receiveBuffer = ByteArray(1024)
                val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
                socket.receive(receivePacket)

                val responseData = receivePacket.data.copyOf(receivePacket.length)

                // Send response back through VPN
                sendUdpThroughVpn(responseData, clientIp, clientPort)

                socket.close()
            } catch (e: Exception) {
                Log.w(TAG, "DNS forward failed: ${e.message}")
            }
        }
    }

    private fun sendUdpThroughVpn(data: ByteArray, destIp: String, destPort: Int) {
        // Build UDP/IP packet and write to VPN interface
        try {
            val ipHeader = PacketUtil.buildIpv4Header(data.size, destIp)
            val udpHeader = PacketUtil.buildUdpHeader(data.size, DNS_PORT, destPort)
            val fullPacket = ipHeader + udpHeader + data
            writePacketToVpn(fullPacket)
        } catch (e: Exception) {
            Log.w(TAG, "VPN write error: ${e.message}")
        }
    }

    private fun writePacketToVpn(packet: ByteArray) {
        try {
            vpnInterface?.fileDescriptor?.let { fd ->
                val outputStream = FileOutputStream(fd)
                outputStream.write(packet)
                outputStream.flush()
            }
        } catch (e: Exception) {
            if (isRunning) {
                Log.w(TAG, "Write to VPN error: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }
}
