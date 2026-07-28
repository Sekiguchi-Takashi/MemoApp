package com.sekiguchi.memoapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * マイク録音を担当するフォアグラウンドサービス。
 * 画面を離れても録音を継続する。停止(手動/タイマー)時にSAFフォルダへ保存し、
 * 履歴をSharedPreferencesに追記してActivityへブロードキャストで通知する。
 */
class RecordService : Service() {

    companion object {
        @Volatile var isRecording = false
        @Volatile var startTime = 0L

        const val ACTION_START = "com.sekiguchi.memoapp.START"
        const val ACTION_STOP = "com.sekiguchi.memoapp.STOP"
        const val EXTRA_MINUTES = "minutes"
        const val BROADCAST = "com.sekiguchi.memoapp.RECORD_EVENT"

        const val PREFS = "voice"
        const val KEY_DIR = "voice_dir"
        const val KEY_HIST = "voice_hist"

        private const val CH_ID = "rec_channel"
        private const val NOTIF_ID = 1
    }

    private var recorder: MediaRecorder? = null
    private var tempFile: File? = null
    private val handler = Handler(Looper.getMainLooper())
    private var autoStop: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent.getIntExtra(EXTRA_MINUTES, 60))
            ACTION_STOP -> stopAndSave()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(minutes: Int) {
        if (isRecording) return
        createChannel()
        goForeground()

        val tmp = File(cacheDir, "rec_temp.m4a")
        tempFile = tmp
        try {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128000)
            r.setAudioSamplingRate(44100)
            r.setOutputFile(tmp.absolutePath)
            r.prepare()
            r.start()
            recorder = r
        } catch (e: Exception) {
            isRecording = false
            stopForegroundCompat()
            stopSelf()
            return
        }

        isRecording = true
        startTime = System.currentTimeMillis()
        sendBroadcast(Intent(BROADCAST).setPackage(packageName))

        val runnable = Runnable { stopAndSave() }
        autoStop = runnable
        handler.postDelayed(runnable, minutes.toLong() * 60_000L)
    }

    private fun stopAndSave() {
        if (!isRecording) return
        autoStop?.let { handler.removeCallbacks(it) }
        autoStop = null

        val elapsed = System.currentTimeMillis() - startTime
        val begun = startTime
        try { recorder?.stop() } catch (_: Exception) { }
        try { recorder?.release() } catch (_: Exception) { }
        recorder = null
        isRecording = false

        saveToSaf(tempFile, begun, elapsed)

        sendBroadcast(Intent(BROADCAST).setPackage(packageName))
        stopForegroundCompat()
        stopSelf()
    }

    private fun saveToSaf(src: File?, begun: Long, elapsedMs: Long) {
        if (src == null || !src.exists()) return
        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        val dir = sp.getString(KEY_DIR, null) ?: return
        try {
            val treeUri = Uri.parse(dir)
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            val name = buildName(begun, elapsedMs)
            val fileUri = DocumentsContract.createDocument(
                contentResolver, dirUri, "audio/mp4", name
            ) ?: return
            contentResolver.openOutputStream(fileUri)?.use { out ->
                src.inputStream().use { input -> input.copyTo(out) }
            }
            src.delete()
            addHistory(sp, name, fileUri.toString(), begun)
        } catch (_: Exception) { }
    }

    private fun buildName(begun: Long, elapsedMs: Long): String {
        val date = SimpleDateFormat("yyyyMMdd_HHmm", Locale.JAPAN).format(Date(begun))
        val totalSec = (elapsedMs / 1000).toInt()
        val m = totalSec / 60
        val s = totalSec % 60
        val dur = if (s == 0) "${m}分" else "${m}分${s}秒"
        return "録音(${date} ${dur}).m4a"
    }

    private fun addHistory(sp: android.content.SharedPreferences, name: String, uri: String, t: Long) {
        val arr = try { JSONArray(sp.getString(KEY_HIST, "[]")) } catch (_: Exception) { JSONArray() }
        arr.put(JSONObject().put("n", name).put("u", uri).put("t", t))
        sp.edit().putString(KEY_HIST, arr.toString()).apply()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CH_ID, "録音", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }

    private fun goForeground() {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CH_ID) else @Suppress("DEPRECATION") Notification.Builder(this)
        val notif = builder
            .setContentTitle("録音中")
            .setContentText("メモ帳アプリが録音しています")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) {
            try { recorder?.stop() } catch (_: Exception) { }
            try { recorder?.release() } catch (_: Exception) { }
            isRecording = false
        }
    }
}
