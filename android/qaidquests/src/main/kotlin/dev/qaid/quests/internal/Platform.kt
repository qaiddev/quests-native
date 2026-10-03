package dev.qaid.quests.internal

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.LruCache
import dev.qaid.quests.QaidQuests
import dev.qaid.quests.core.DeviceInfo
import dev.qaid.quests.core.HttpRequest
import dev.qaid.quests.core.HttpResponse
import dev.qaid.quests.core.PendingFlusher
import dev.qaid.quests.core.PendingResponse
import dev.qaid.quests.core.PendingStore
import dev.qaid.quests.core.QuestTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal const val TAG = "QaidQuests"

/** The real network. Android's HttpURLConnection speaks PATCH (the JVM's does not). */
internal object HttpTransport : QuestTransport {
    override suspend fun send(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val conn = URL(request.url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = request.method
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.useCaches = false
            request.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            request.body?.let { body ->
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            HttpResponse(status, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            conn.disconnect()
        }
    }
}

internal object Environment {
    fun deviceInfo(context: Context, appName: String): DeviceInfo {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        @Suppress("DEPRECATION")
        val build = if (Build.VERSION.SDK_INT >= 28) info?.longVersionCode?.toString() else info?.versionCode?.toString()
        return DeviceInfo(
            osVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
            model = DeviceInfo.model(Build.MANUFACTURER, Build.MODEL),
            appName = appName,
            appVersion = info?.versionName ?: "0",
            build = build ?: "0",
            locale = Locale.getDefault().toLanguageTag(),
        )
    }
}

/**
 * One random id per install, so qaid can group one person's responses without knowing who
 * they are. Kept where the feedback SDK keeps its own, so a person's feedback and quest
 * responses carry the same id (the web embed's `visitorId`).
 */
internal object VisitorId {
    private const val PREFS = "dev.qaid.feedback"
    private const val KEY = "visitorId"

    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { return it }
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(KEY, fresh).apply()
        return fresh
    }
}

/**
 * Responses that couldn't reach qaid, in `noBackupFilesDir/qaid-quests-queue/`, sent when the
 * SDK is configured, when the app comes to the foreground, and when a network comes up.
 */
internal object PendingQueue {
    private const val DIR = "qaid-quests-queue"
    private val flushing = AtomicBoolean(false)
    private val watching = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null

    fun store(context: Context) = PendingStore(File(context.noBackupFilesDir, DIR))

    /** Remembers the app and starts watching for the moments to flush. Idempotent. */
    fun attach(context: Context) {
        val app = context.applicationContext ?: context
        if (appContext == null) appContext = app
        if (!watching.compareAndSet(false, true)) return
        (app as? Application)?.registerActivityLifecycleCallbacks(object : ActivityCallbacks() {
            private var started = 0
            override fun onActivityStarted(activity: Activity) {
                if (started++ == 0) flush()
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
            }
        })
        try {
            app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = flush()
                },
            )
        } catch (_: RuntimeException) {
            // No ACCESS_NETWORK_STATE (stripped by the app's manifest): launch and foreground still flush.
        }
    }

    fun save(context: Context, pending: PendingResponse): Boolean = store(context).save(pending)

    /** Sends what's saved, oldest first, one flush at a time. */
    fun flush() {
        val context = appContext ?: return
        if (!QaidQuests.isConfigured || !flushing.compareAndSet(false, true)) return
        scope.launch {
            try {
                val sent = PendingFlusher(store(context), QaidQuests.transport, onRefused = { op, error ->
                    QaidQuests.report("saved", op, error)
                }).flush()
                if (sent > 0) Log.i(TAG, "sent $sent saved quest response(s)")
            } catch (e: Exception) {
                Log.w(TAG, "sending saved quest responses failed; will try again", e)
            } finally {
                flushing.set(false)
            }
        }
    }
}

/** Option images: fetched once, decoded no larger than needed, kept in a small memory cache. */
internal object ImageCache {
    private const val MAX_PX = 512
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(url: String): Bitmap? = cache.get(url)

    suspend fun load(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = URL(url).openStream().use { it.readBytes() }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PX) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()?.also { cache.put(url, it) }
        }
    }
}

/** ActivityLifecycleCallbacks with every method empty, so each user overrides only what it needs. */
internal open class ActivityCallbacks : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
