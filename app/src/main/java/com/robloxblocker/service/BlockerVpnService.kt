package com.robloxblocker.service

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.robloxblocker.util.DnsPacket
import com.robloxblocker.util.PacketUtil
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class BlockerVpnService : VpnService() {

    companion object {
        private const val TAG = "BlockerVPN"
        private const val VPN_MTU = 1500
        private const val DNS_PORT = 53
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val DNS_FORWARDER_ADDRESS = "10.0.0.1"

        private val DNS_SERVERS = listOf(
            "8.8.8.8",
            "1.1.1.1"
        )
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnInput: FileInputStream? = null
    private var vpnOutput: FileOutputStream? = null
    private val isRunning = AtomicBoolean(false)
    private val blockedCount = AtomicInteger(0)
    private val totalDnsQueries = AtomicInteger(0)

    // Thread pool for DNS forwarding (bounded to prevent OOM)
    private val dnsExecutor = Executors.newFixedThreadPool(4)

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
        "robloxlabs.com",
        "roblox.eco",
        "rplus.com",
        "robloxdev.com",
        "robloxwiki.com",
        "rbdn.com",
        "rbxcdn.com",
        "roblox-uploads.s3.amazonaws.com",
        "roblox.com.s3.amazonaws.com"
    )

    override fun onCreate() {
        super.onCreate()
        // Ensure notification channel exists before any notification is posted
        BlockerNotification.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_VPN") {
            stopVpn()
            return START_NOT_STICKY
        }

        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning.get()) return

        try {
            // Ensure notification channel
            BlockerNotification.createChannels(this)
            startForegroundNotification()

            // Build VPN with proper IP configuration
            val builder = Builder().apply {
                setSession("SecureBrowse-Blocker")
                setMtu(VPN_MTU)

                // VPN interface needs an IP address
                addAddress(VPN_ADDRESS, 24)

                // Route all traffic through VPN
                addRoute("0.0.0.0", 0)
                addDnsServer(DNS_SERVERS[0])
                addDnsServer(DNS_SERVERS[1])

                // Allow bypass for our own DNS forwarder
                addRoute(DNS_FORWARDER_ADDRESS, 32)
            }

            val fd = builder.establish()
            if (fd == null) {
                Log.e(TAG, "VPN establish() returned null — permission may have been revoked")
                stopSelf()
                return
            }

            vpnInterface = fd
            vpnInput = FileInputStream(fd.fileDescriptor)
            vpnOutput = FileOutputStream(fd.fileDescriptor)
            isRunning.set(true)
            blockedCount.set(0)
            totalDnsQueries.set(0)

            Log.i(TAG, "VPN started — blocking ${blockedDomains.size} Roblox domains")

            // Single thread reads from VPN fd
            Thread({ processVpnPackets() }, "VPN-Reader").start()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN: ${e.message}", e)
            stopVpn()
        }
    }

    private fun stopVpn() {
        if (!isRunning.compareAndSet(true, false)) return

        Log.i(TAG, "Stopping VPN. Blocked ${blockedCount.get()} / ${totalDnsQueries.get()} queries.")

        try { vpnInput?.close() } catch (_: Exception) {}
        try { vpnOutput?.close() } catch (_: Exception) {}
        try { vpnInterface?.close() } catch (_: Exception) {}

        vpnInput = null
        vpnOutput = null
        vpnInterface = null

        dnsExecutor.shutdownNow()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundNotification() {
        try {
            val notification = BlockerNotification.createRunningNotification(
                this,
                "SecureBrowse Active",
                "Protecting against restricted domains",
                blockedCount.get()
            )
            startForeground(BlockerNotification.NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show foreground notification: ${e.message}", e)
        }
    }

    /**
     * Main loop: reads raw IP packets from VPN, processes DNS, writes back
     */
    private fun processVpnPackets() {
        val input = vpnInput ?: return
        val buffer = ByteArray(VPN_MTU)

        while (isRunning.get()) {
            try {
                val length = input.read(buffer)
                if (length <= 0) continue

                val packet = buffer.copyOf(length)
                val ipVersion = packet[0].toInt() shr 4 and 0x0F

                when (ipVersion) {
                    4 -> handleIPv4(packet)
                    6 -> handleIPv6(packet)
                    // else: drop
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.w(TAG, "VPN read error: ${e.message}")
                }
                break
            }
        }
    }

    private fun handleIPv4(packet: ByteArray) {
        if (packet.size < 28) return // Need at least IP(20) + UDP(8)

        val protocol = packet[9].toInt() and 0xFF

        // Only handle UDP DNS queries (protocol=17, dstPort=53)
        if (protocol == 17) {
            val dstPort = ((packet[22].toInt() and 0xFF) shl 8) or (packet[23].toInt() and 0xFF)
            val srcPort = ((packet[20].toInt() and 0xFF) shl 8) or (packet[21].toInt() and 0xFF)

            if (dstPort == DNS_PORT) {
                val ipHeaderLen = (packet[0].toInt() and 0x0F) * 4
                val dnsData = packet.copyOfRange(ipHeaderLen + 8, packet.size)
                handleDnsQuery(dnsData, srcPort, packet)
                return
            }
        }

        // For non-DNS traffic: forward through a protected raw socket to reach the real network
        forwardNonDnsPacket(packet)
    }

    private fun handleIPv6(packet: ByteArray) {
        if (packet.size < 58) return

        val nextHeader = packet[6].toInt() and 0xFF
        if (nextHeader == 17) { // UDP
            val dstPort = ((packet[54].toInt() and 0xFF) shl 8) or (packet[55].toInt() and 0xFF)
            if (dstPort == DNS_PORT) {
                // Parse IPv6 DNS — simplified: extract data after fixed header
                val dnsData = packet.copyOfRange(58, packet.size)
                val srcPort = ((packet[52].toInt() and 0xFF) shl 8) or (packet[53].toInt() and 0xFF)
                handleDnsQuery(dnsData, srcPort, packet)
                return
            }
        }
        // For non-DNS IPv6 traffic: forward through a protected raw socket
        forwardNonDnsPacket(packet)
    }

    private fun handleDnsQuery(dnsData: ByteArray, clientPort: Int, originalPacket: ByteArray) {
        totalDnsQueries.incrementAndGet()

        try {
            val dnsPacket = DnsPacket(dnsData)
            val domain = dnsPacket.getQueryDomain()

            if (domain != null && isBlockedDomain(domain)) {
                blockedCount.incrementAndGet()
                Log.d(TAG, "BLOCKED: $domain")

                // Build NXDOMAIN response and inject back
                val response = buildNxdomainResponse(dnsData)
                injectDnsResponse(response, clientPort, originalPacket)

                // Update notification (throttled — max once per second)
                BlockerNotification.updateBlockedCount(this, blockedCount.get())
                return
            }

            // Forward to real DNS server
            forwardDns(dnsData, clientPort, originalPacket)

        } catch (e: Exception) {
            Log.w(TAG, "DNS error: ${e.message}")
            // If we can't parse, forward as-is
            forwardDns(dnsData, clientPort, originalPacket)
        }
    }

    private fun isBlockedDomain(domain: String): Boolean {
        val lower = domain.lowercase().trimEnd('.')
        return blockedDomains.any { lower == it || lower.endsWith(".$it") }
    }

    private fun buildNxdomainResponse(query: ByteArray): ByteArray {
        val response = query.copyOf()
        // QR=1 (response), RCODE=3 (NXDOMAIN)
        response[2] = (response[2].toInt() or 0x80).toByte()
        response[3] = ((response[3].toInt() and 0xF0) or 0x03).toByte()
        // ANCOUNT = 0
        response[6] = 0
        response[7] = 0
        return response
    }

    private fun injectDnsResponse(dnsResponse: ByteArray, clientPort: Int, originalPacket: ByteArray) {
        try {
            // Swap src/dst in original IP+UDP header to make a response
            val modified = originalPacket.copyOf()

            // Swap IP source/dest (bytes 12-15 <-> 16-19)
            for (i in 12..19) {
                modified[i] = originalPacket[if (i < 16) i + 4 else i - 4]
            }

            // Swap UDP src/dst ports (bytes 20-21 <-> 22-23)
            modified[20] = originalPacket[22]
            modified[21] = originalPacket[23]
            modified[22] = originalPacket[20]
            modified[23] = originalPacket[21]

            // Recalculate IP checksum
            modified[10] = 0
            modified[11] = 0
            val checksum = PacketUtil.calculateIpChecksum(modified, 20)
            modified[10] = (checksum shr 8).toByte()
            modified[11] = checksum.toByte()

            // Replace payload with DNS response
            val ipHeaderLen = (modified[0].toInt() and 0x0F) * 4
            val udpHeaderLen = 8
            val totalLen = ipHeaderLen + udpHeaderLen + dnsResponse.size

            // Update IP total length
            modified[2] = (totalLen shr 8).toByte()
            modified[3] = totalLen.toByte()

            // Update UDP length
            modified[ipHeaderLen + 4] = ((udpHeaderLen + dnsResponse.size) shr 8).toByte()
            modified[ipHeaderLen + 5] = (udpHeaderLen + dnsResponse.size).toByte()

            // Write full packet: header + DNS response
            val fullPacket = modified.copyOf(ipHeaderLen + udpHeaderLen) + dnsResponse
            writeToVpn(fullPacket)
        } catch (e: Exception) {
            Log.w(TAG, "Inject DNS response error: ${e.message}")
        }
    }

    private fun forwardDns(dnsData: ByteArray, clientPort: Int, originalPacket: ByteArray) {
        dnsExecutor.execute {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.soTimeout = 5000

                val server = InetAddress.getByName(DNS_SERVERS.first())
                val sendPkt = DatagramPacket(dnsData, dnsData.size, server, DNS_PORT)
                socket.send(sendPkt)

                val recvBuf = ByteArray(1024)
                val recvPkt = DatagramPacket(recvBuf, recvBuf.size)
                socket.receive(recvPkt)

                val responseData = recvPkt.data.copyOf(recvPkt.length)
                injectDnsResponse(responseData, clientPort, originalPacket)

            } catch (e: Exception) {
                Log.w(TAG, "DNS forward error: ${e.message}")
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Forward non-DNS packets to the real network using a protected UDP socket.
     * This prevents routing loops (VPN trying to handle its own outgoing traffic).
     */
    private fun forwardNonDnsPacket(packet: ByteArray) {
        try {
            val socket = java.net.DatagramSocket()
            protect(socket)
            socket.close()
            // Write the packet back to the VPN interface so the OS routes it properly
            writeToVpn(packet)
        } catch (e: Exception) {
            // Silently drop if forwarding fails — non-critical for DNS blocking
            Log.w(TAG, "Non-DNS forward error: ${e.message}")
        }
    }

    private fun writeToVpn(packet: ByteArray) {
        try {
            vpnOutput?.write(packet)
            vpnOutput?.flush()
        } catch (e: Exception) {
            if (isRunning.get()) {
                Log.w(TAG, "VPN write error: ${e.message}")
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
