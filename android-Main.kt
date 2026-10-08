package com.catatduit.app

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject

/** Antrean transaksi dari notifikasi + catatan uji, disimpan di HP. */
object Store {
    private const val P = "catatduit"

    @Synchronized
    fun add(c: Context, type: String, amount: Long, note: String, ts: Long, key: String): Boolean {
        val sp = c.getSharedPreferences(P, Context.MODE_PRIVATE)
        val seen = JSONArray(sp.getString("seen", "[]"))
        for (i in 0 until seen.length()) if (seen.getString(i) == key) return false // anti dobel
        seen.put(key)
        val trimmed = JSONArray()
        for (i in maxOf(0, seen.length() - 200) until seen.length()) trimmed.put(seen.get(i))
        val pend = JSONArray(sp.getString("pending", "[]"))
        pend.put(JSONObject().put("type", type).put("amount", amount).put("note", note).put("ts", ts))
        sp.edit().putString("seen", trimmed.toString()).putString("pending", pend.toString()).apply()
        return true
    }

    @Synchronized
    fun take(c: Context): String {
        val sp = c.getSharedPreferences(P, Context.MODE_PRIVATE)
        val v = sp.getString("pending", "[]") ?: "[]"
        sp.edit().putString("pending", "[]").apply()
        return v
    }

    // 12 notifikasi terakhir yang memuat "Rp" (dari aplikasi apa pun), untuk menu uji. Hanya tersimpan di HP.
    @Synchronized
    fun log(c: Context, line: String) {
        val sp = c.getSharedPreferences(P, Context.MODE_PRIVATE)
        val a = JSONArray(sp.getString("log", "[]"))
        a.put(line)
        val t = JSONArray()
        for (i in maxOf(0, a.length() - 12) until a.length()) t.put(a.get(i))
        sp.edit().putString("log", t.toString()).apply()
    }

    @Synchronized
    fun logText(c: Context): String {
        val a = JSONArray(c.getSharedPreferences(P, Context.MODE_PRIVATE).getString("log", "[]"))
        return (0 until a.length()).joinToString("\n\n") { a.getString(it) }
    }
}

class NotifService : NotificationListenerService() {
    // Nama paket aplikasi. Cek yang sebenarnya lewat menu uji di Pengaturan, lalu sesuaikan.
    private val apps = mapOf("id.dana" to "DANA", "com.gojek.app" to "GoPay", "com.gojek.gopay" to "GoPay")
    // Kata kunci di teks notifikasi. Sesuaikan dengan notifikasi asli di HP kamu.
    private val inKw = listOf("menerima", "diterima", "uang masuk", "top up berhasil", "terima uang")
    private val outKw = listOf("membayar", "pembayaran berhasil", "transfer berhasil", "kirim uang", "dikirim")
    private val trackOut = false // ubah ke true jika uang keluar juga mau dicatat otomatis

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val ex = sbn.notification.extras
        val text = listOfNotNull(
            ex.getCharSequence(Notification.EXTRA_TITLE),
            ex.getCharSequence(Notification.EXTRA_TEXT),
            ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
        ).joinToString(" ")
        if (text.contains("Rp")) Store.log(this, sbn.packageName + "\n" + text.take(200))
        val name = apps[sbn.packageName] ?: return
        val low = text.lowercase()
        val amount = Regex("""Rp\s?([0-9][0-9.]*)""").find(text)
            ?.groupValues?.get(1)?.replace(".", "")?.toLongOrNull() ?: return
        val type = when {
            inKw.any { low.contains(it) } -> "in"
            trackOut && outKw.any { low.contains(it) } -> "out"
            else -> return
        }
        Store.add(this, type, amount, "Otomatis · $name", sbn.postTime, "${sbn.packageName}|${sbn.postTime}|$amount")
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private var asked = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                loader.shouldInterceptRequest(request.url)
        }
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web.canGoBack()) web.goBack() else finish() }
        })
    }

    override fun onResume() { super.onResume(); checkAccess() }

    private fun checkAccess() {
        val on = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        if (!on && !asked) {
            asked = true
            AlertDialog.Builder(this).setTitle("Izinkan akses notifikasi")
                .setMessage("Agar uang masuk dari DANA/GoPay tercatat otomatis, aktifkan akses notifikasi untuk CatatDuit.")
                .setPositiveButton("Buka pengaturan") { _, _ ->
                    startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                }
                .setNegativeButton("Nanti", null).show()
        }
    }

    inner class Bridge {
        @JavascriptInterface fun takePending(): String = Store.take(this@MainActivity)
        @JavascriptInterface fun debugLog(): String = Store.logText(this@MainActivity)
    }
}
