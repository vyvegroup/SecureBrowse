package com.robloxblocker

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AnticipateInterpolator
import android.view.animation.DecelerateInterpolator
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var rootLayout: View

    private val restrictedDomains = listOf(
        "roblox.com",
        "www.roblox.com",
        "web.roblox.com",
        "roblox.com/*",
        "*.roblox.com"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupEdgeToEdge()
        initViews()
        setupWebView()
        runEntranceAnimations()
        populateDynamicData()
        setupButtons()
    }

    private fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    private fun initViews() {
        webView = findViewById(R.id.webview)
        rootLayout = findViewById(R.id.root)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            databaseEnabled = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url.toString()
                return isRestrictedDomain(url)
            }
        }

        // Load roblox.com - will trigger restriction page
        webView.loadUrl("https://roblox.com")
    }

    private fun isRestrictedDomain(url: String): Boolean {
        return restrictedDomains.any { domain ->
            url.contains(domain, ignoreCase = true)
        }
    }

    private fun runEntranceAnimations() {
        val iconContainer = findViewById<View>(R.id.icon_container)
        val errorBadge = findViewById<View>(R.id.error_badge)
        val tvTitle = findViewById<View>(R.id.tv_title)
        val tvSubtitle = findViewById<View>(R.id.tv_subtitle)
        val glassCard = findViewById<CardView>(R.id.glass_card)
        val buttonsContainer = findViewById<View>(R.id.buttons_container)
        val policyLinks = findViewById<View>(R.id.policy_links)
        val tvFooter = findViewById<View>(R.id.tv_footer)
        val glowOrbTop = findViewById<View>(R.id.glow_orb_top)
        val glowOrbBottom = findViewById<View>(R.id.glow_orb_bottom)

        // Start everything invisible
        listOf(
            iconContainer, errorBadge, tvTitle, tvSubtitle,
            glassCard, buttonsContainer, policyLinks, tvFooter
        ).forEach { it.alpha = 0f }

        // Animate glow orbs
        animateGlowOrb(glowOrbTop, 0f, 0.5f, 3000)
        animateGlowOrb(glowOrbBottom, 0f, 0.4f, 3500, delay = 200)

        // Staggered entrance animations
        animateEntrance(iconContainer, 150, -30f, 0f, 600)
        animateEntrance(errorBadge, 350, -20f, 0f, 500)
        animateEntrance(tvTitle, 500, -20f, 0f, 550)
        animateEntrance(tvSubtitle, 650, -15f, 0f, 500)
        animateEntrance(glassCard, 800, 40f, 0f, 700, useDecelerate = true)
        animateEntrance(buttonsContainer, 1100, 20f, 0f, 500)
        animateEntrance(policyLinks, 1300, 10f, 0f, 400)
        animateEntrance(tvFooter, 1400, 10f, 0f, 400)

        // Pulse animation on icon
        iconContainer.postDelayed({
            startPulseAnimation(iconContainer)
        }, 800)
    }

    private fun animateGlowOrb(view: View, fromAlpha: Float, toAlpha: Float, duration: Long, delay: Long = 0) {
        view.alpha = fromAlpha
        view.animate()
            .alpha(toAlpha)
            .setDuration(duration)
            .setStartDelay(delay)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()

        // Subtle floating animation
        view.postDelayed({
            val floatAnim = ObjectAnimator.ofFloat(view, "translationY", 0f, -20f, 0f, 15f, 0f)
            floatAnim.duration = 8000
            floatAnim.interpolator = AccelerateDecelerateInterpolator()
            floatAnim.repeatCount = ObjectAnimator.INFINITE
            floatAnim.start()
        }, delay)
    }

    private fun animateEntrance(
        view: View,
        delay: Long,
        fromTranslationY: Float,
        toTranslationY: Float,
        duration: Long,
        useDecelerate: Boolean = false
    ) {
        view.translationY = fromTranslationY
        view.alpha = 0f

        val interpolator = if (useDecelerate) DecelerateInterpolator(1.8f)
        else AnticipateInterpolator(1.2f)

        view.animate()
            .translationY(toTranslationY)
            .alpha(1f)
            .setDuration(duration)
            .setStartDelay(delay)
            .setInterpolator(interpolator)
            .start()
    }

    private fun startPulseAnimation(view: View) {
        val scaleX = ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.05f, 1f)
        val scaleY = ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.05f, 1f)
        scaleX.duration = 2000
        scaleY.duration = 2000
        scaleX.interpolator = AccelerateDecelerateInterpolator()
        scaleY.interpolator = AccelerateDecelerateInterpolator()
        scaleX.repeatCount = ObjectAnimator.INFINITE
        scaleY.repeatCount = ObjectAnimator.INFINITE
        scaleX.start()
        scaleY.start()
    }

    private fun populateDynamicData() {
        val refId = UUID.randomUUID().toString().substring(0, 8).uppercase()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        findViewById<android.widget.TextView>(R.id.tv_ref_id).text = "REF-$refId"

        // Add timestamp dynamically to description
        val description = findViewById<android.widget.TextView>(R.id.tv_description)
        description.text = getString(R.string.restricted_description) + "\n\nTimestamp: $timestamp"
    }

    private fun setupButtons() {
        findViewById<MaterialButton>(R.id.btn_go_back).setOnClickListener {
            runExitAnimation()
        }

        findViewById<MaterialButton>(R.id.btn_report).setOnClickListener {
            Toast.makeText(this, "Report submitted. Thank you.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runExitAnimation() {
        val glassCard = findViewById<CardView>(R.id.glass_card)
        val buttonsContainer = findViewById<View>(R.id.buttons_container)
        val policyLinks = findViewById<View>(R.id.policy_links)
        val tvFooter = findViewById<View>(R.id.tv_footer)

        glassCard.animate()
            .alpha(0f)
            .translationY(30f)
            .setDuration(300)
            .setInterpolator(AnticipateInterpolator())
            .start()

        buttonsContainer.animate()
            .alpha(0f)
            .translationY(20f)
            .setDuration(250)
            .setStartDelay(50)
            .start()

        policyLinks.animate().alpha(0f).setDuration(200).setStartDelay(100).start()
        tvFooter.animate().alpha(0f).setDuration(200).setStartDelay(150).start()

        // Show WebView after exit animation
        rootLayout.postDelayed({
            rootLayout.animate()
                .alpha(0f)
                .setDuration(400)
                .withEndAction {
                    webView.visibility = View.VISIBLE
                    rootLayout.visibility = View.GONE
                }
                .start()
        }, 400)
    }

    override fun onBackPressed() {
        if (webView.visibility == View.VISIBLE && webView.canGoBack()) {
            webView.goBack()
        } else if (webView.visibility == View.VISIBLE) {
            // Show restricted page again
            webView.visibility = View.GONE
            rootLayout.visibility = View.VISIBLE
            rootLayout.alpha = 1f
            runEntranceAnimations()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
