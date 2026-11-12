package com.uberauto

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 只「讀」Uber Driver 畫面，不會點任何按鈕。
 * 偵測到派單畫面 → 解析價格與距離 → 每公里達標就跳通知。
 */
class UberAccessibilityService : AccessibilityService() {

    companion object {
        const val UBER_PKG = "com.ubercab.driver"
        const val CHANNEL_ID = "good_offer"
        private val OFFER_KEYWORDS = listOf("接受", "接單", "Accept", "ACCEPT", "接受行程", "Match")
        private const val SETTLE_MS = 600L      // 等畫面穩定再讀，避免讀到載入中的半成品
        private const val OFFER_RESET_MS = 30_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var decidedForCurrentOffer = false
    private var offerStartTime = 0L
    private var notifyId = 1000

    private val evaluateRunnable = Runnable { evaluate() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        createChannel(this)
        Prefs(this).addLog("服務已啟動")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != UBER_PKG) return
        if (!Prefs(this).enabled) return
        handler.removeCallbacks(evaluateRunnable)
        handler.postDelayed(evaluateRunnable, SETTLE_MS)
    }

    private fun evaluate() {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != UBER_PKG) return

        val texts = mutableListOf<String>()
        val descs = mutableListOf<String>()
        collect(root, texts, descs)

        val isOfferScreen = (texts + descs).any { t ->
            t.length <= 15 && OFFER_KEYWORDS.any { k -> t.contains(k) }
        }
        val now = System.currentTimeMillis()

        if (!isOfferScreen) {
            decidedForCurrentOffer = false
            return
        }
        if (decidedForCurrentOffer && now - offerStartTime < OFFER_RESET_MS) return

        decidedForCurrentOffer = true
        offerStartTime = now

        val prefs = Prefs(this)
        // 先用畫面文字解析，失敗再改用內容描述
        var result = OfferParser.parse(texts.distinct())
        if (result is ParseResult.Unclear && descs.isNotEmpty()) {
            val alt = OfferParser.parse(descs.distinct())
            if (alt is ParseResult.Ok) result = alt
        }
        prefs.lastScreenText = (texts.distinct() + listOf("--- 描述 ---") + descs.distinct())
            .joinToString("\n")

        val d = DecisionMaker.decide(result, prefs.minPerKm.toDouble(), prefs.includePickup)
        if (d.notify) {
            prefs.addLog("🔔 好單：${d.message}")
            showNotification(d.message)
        } else {
            prefs.addLog(d.message)
        }
    }

    private fun collect(node: AccessibilityNodeInfo?, texts: MutableList<String>, descs: MutableList<String>) {
        if (node == null) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(descs::add)
        for (i in 0 until node.childCount) collect(node.getChild(i), texts, descs)
    }

    private fun showNotification(message: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openUber = packageManager.getLaunchIntentForPackage(UBER_PKG)
        val pi = openUber?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("💰 好單！要接嗎？")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setTimeoutAfter(30_000)
            .apply { if (pi != null) setContentIntent(pi) }
            .build()
        nm.notify(notifyId++, n)
    }

    override fun onInterrupt() {}
}

fun createChannel(context: Context) {
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (nm.getNotificationChannel(UberAccessibilityService.CHANNEL_ID) != null) return
    val ch = NotificationChannel(
        UberAccessibilityService.CHANNEL_ID, "好單提醒", NotificationManager.IMPORTANCE_HIGH
    ).apply {
        description = "每公里價格達標的派單"
        enableVibration(true)
        vibrationPattern = longArrayOf(0, 400, 200, 400)
        setSound(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build()
        )
    }
    nm.createNotificationChannel(ch)
}
