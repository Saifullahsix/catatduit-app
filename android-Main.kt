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
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.Calendar
import java.util.concurrent.TimeUnit
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

    private fun showBio() {
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    web.evaluateJavascript("window.onBio&&onBio(true)", null)
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    web.evaluateJavascript("window.onBio&&onBio(false)", null)
                }
            })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Buka CatatDuit")
            .setSubtitle("Gunakan sidik jari atau wajah")
            .setNegativeButtonText("Pakai PIN")
            .build()
        prompt.authenticate(info)
    }

    inner class Bridge {
        @JavascriptInterface fun takePending(): String = Store.take(this@MainActivity)
        @JavascriptInterface fun debugLog(): String = Store.logText(this@MainActivity)

        @JavascriptInterface fun bioAvailable(): Boolean =
            BiometricManager.from(this@MainActivity)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

        @JavascriptInterface fun bioAuth() { runOnUiThread { showBio() } }

        @JavascriptInterface fun saveBackup(json: String): Boolean = Backup.save(this@MainActivity, json)

        @JavascriptInterface fun setReminder(h: Int, m: Int, on: Boolean) {
            if (on && Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                runOnUiThread { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7) }
            }
            Reminder.set(this@MainActivity, h, m, on)
        }
    }
}

/** Menyimpan backup ke folder Download (Android 10 ke atas). */
object Backup {
    fun save(c: Context, json: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            val name = "CatatDuit-backup.json"
            val r = c.contentResolver
            val col = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            var uri: Uri? = null
            r.query(col, arrayOf(MediaStore.Downloads._ID), "${MediaStore.Downloads.DISPLAY_NAME}=?", arrayOf(name), null)?.use {
                if (it.moveToFirst()) uri = ContentUris.withAppendedId(col, it.getLong(0))
            }
            if (uri == null) {
                val v = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
                }
                uri = r.insert(col, v)
            }
            val u = uri ?: return false
            r.openOutputStream(u, "wt")?.use { it.write(json.toByteArray()) }
            true
        } catch (e: Exception) {
            false
        }
    }
}

/** Pengingat harian lewat WorkManager (waktu bisa meleset beberapa menit karena hemat baterai). */
class ReminderWorker(c: Context, p: WorkerParameters) : Worker(c, p) {
    override fun doWork(): Result {
        val ctx = applicationContext
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("rem", "Pengingat", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, "rem")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("CatatDuit")
            .setContentText("Sudah catat transaksi hari ini?")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(1, n)
        return Result.success()
    }
}

object Reminder {
    fun set(c: Context, h: Int, m: Int, on: Boolean) {
        val wm = WorkManager.getInstance(c)
        if (!on) { wm.cancelUniqueWork("rem"); return }
        val now = Calendar.getInstance()
        val t = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0)
        }
        if (t.before(now)) t.add(Calendar.DAY_OF_YEAR, 1)
        val req = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(t.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
            .build()
        wm.enqueueUniquePeriodicWork("rem", ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}
