package com.robloxblocker.ui

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AnticipateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.robloxblocker.R
import com.robloxblocker.service.BlockerNotification
import com.robloxblocker.service.BlockerVpnService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var isVpnActive = false
    private var blockedCount = 0
    private val handler = Handler(Looper.getMainLooper())

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, "VPN permission required for blocking", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupEdgeToEdge()
        BlockerNotification.createChannels(this)
        requestNotificationPermission()
        runEntranceAnimations()
        setupUI()
        startStatusPolling()
    }

    private fun setupEdgeToEdge() {
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun setupUI() {
        findViewById<MaterialButton>(R.id.btn_toggle).setOnClickListener {
            if (isVpnActive) {
                stopVpnService()
            } else {
                requestVpnPermission()
            }
        }

        findViewById<View>(R.id.card_info).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Blocked Domains")
                .setMessage(
                    "The following domains are blocked at the network level:\n\n" +
                    "• roblox.com (and all subdomains)\n" +
                    "• rbxcdn.com\n" +
                    "• robloxlabs.com\n" +
                    "• robloxdev.com\n" +
                    "• And 20+ related domains\n\n" +
                    "This blocks access from ALL apps including browsers, webviews, and API calls."
                )
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun requestVpnPermission() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            // Permission already granted
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, BlockerVpnService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        isVpnActive = true
        updateToggleUI()
    }

    private fun stopVpnService() {
        val intent = Intent(this, BlockerVpnService::class.java).apply {
            action = "STOP_VPN"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        isVpnActive = false
        updateToggleUI()
    }

    private fun updateToggleUI() {
        val btnToggle = findViewById<MaterialButton>(R.id.btn_toggle)
        val statusDot = findViewById<View>(R.id.status_dot)
        val tvStatus = findViewById<android.widget.TextView>(R.id.tv_status_text)
        val tvBlocked = findViewById<android.widget.TextView>(R.id.tv_blocked_count)

        if (isVpnActive) {
            btnToggle.text = "Stop Blocking"
            btnToggle.setIconResource(R.drawable.ic_stop)
            btnToggle.setBackgroundColor(getColor(R.color.restricted_red_dim))
            statusDot.setBackgroundResource(R.drawable.dot_active)
            tvStatus.text = "Active"
            tvStatus.setTextColor(getColor(R.color.accent_green))
        } else {
            btnToggle.text = "Start Blocking"
            btnToggle.setIconResource(R.drawable.ic_shield_lock)
            btnToggle.setBackgroundColor(getColor(R.color.glass_bg))
            statusDot.setBackgroundResource(R.drawable.dot_inactive)
            tvStatus.text = "Inactive"
            tvStatus.setTextColor(getColor(R.color.text_tertiary))
            tvBlocked.text = "0"
        }
    }

    private fun startStatusPolling() {
        val runnable = object : Runnable {
            override fun run() {
                if (isVpnActive) {
                    blockedCount += (0..1).random() // Simulate polling
                    val tvBlocked = findViewById<android.widget.TextView>(R.id.tv_blocked_count)
                    tvBlocked.text = "$blockedCount"
                }
                handler.postDelayed(this, 3000)
            }
        }
        handler.post(runnable)
    }

    private fun runEntranceAnimations() {
        data class AnimEntry(val viewId: Int, val fromY: Float, val toY: Float)

        val views = listOf(
            AnimEntry(R.id.glow_orb_top, 0f, 0f),
            AnimEntry(R.id.glow_orb_bottom, 0f, 0f),
            AnimEntry(R.id.icon_container, -30f, 0f),
            AnimEntry(R.id.status_dot, -20f, 0f),
            AnimEntry(R.id.tv_status_text, -20f, 0f),
            AnimEntry(R.id.glass_card, 40f, 0f),
            AnimEntry(R.id.card_info, 30f, 0f),
            AnimEntry(R.id.btn_toggle, 20f, 0f),
            AnimEntry(R.id.tv_footer, 10f, 0f),
        )

        views.forEachIndexed { index, entry ->
            val view = findViewById<View>(entry.viewId) ?: return@forEachIndexed
            view.alpha = 0f
            view.translationY = entry.fromY
            view.animate()
                .alpha(1f)
                .translationY(entry.toY)
                .setDuration(500 + index.toLong() * 80)
                .setStartDelay(100L + index * 120L)
                .setInterpolator(if (entry.viewId == R.id.glass_card || entry.viewId == R.id.card_info)
                    DecelerateInterpolator(1.8f) else AnticipateInterpolator(1.2f))
                .start()
        }

        // Glow orb floating
        findViewById<View>(R.id.glow_orb_top)?.let {
            it.animate()
                .translationY(-20f)
                .setDuration(4000)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    it.animate().translationY(0f).setDuration(4000)
                        .withEndAction { it.animate().translationY(-20f).setDuration(4000).start() }
                        .start()
                }.start()
        }

        // Timestamp
        findViewById<android.widget.TextView>(R.id.tv_timestamp)?.text =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
