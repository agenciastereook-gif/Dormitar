package ar.stereo.desenchufe

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Escucha la noche con la pantalla apagada y guarda solo los momentos con ruido. */
class NightService : Service() {

    companion object {
        const val CH = "noche"
        const val NID = 11
        const val RATE = 16000
        const val CHUNK = 1600
        const val MAX_CLIPS = 200

        @Volatile var on = false
        @Volatile var startMs = 0L
        @Volatile var nightId = ""
        @Volatile var count = 0
        @Volatile var level = 0f

        fun live(): String = JSONObject()
            .put("on", on).put("start", startMs).put("id", nightId)
            .put("count", count).put("level", level.toDouble()).toString()

        fun dir(ctx: Context) = File(ctx.filesDir, "nights").apply { mkdirs() }
    }

    @Volatile private var stopFlag = false
    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            stopFlag = true
            return START_NOT_STICKY
        }
        if (on) return START_NOT_STICKY
        val thr = intent?.getFloatExtra("thr", 12f) ?: 12f
        val sens = intent?.getStringExtra("sens") ?: "media"

        channel()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NID, notification())
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "desenchufe:noche").apply {
            setReferenceCounted(false)
            acquire(14L * 3600 * 1000)
        }

        stopFlag = false
        on = true
        startMs = System.currentTimeMillis()
        nightId = "n$startMs"
        count = 0
        worker = Thread({
            try {
                listen(thr, sens)
            } catch (e: Throwable) {
                Log.e("Desenchufe", "noche", e)
            } finally {
                finish()
            }
        }, "noche").also { it.start() }
        return START_NOT_STICKY
    }

    private fun channel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH, "Grabación de la noche", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).putExtra("go", "dormir"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, NightService::class.java).setAction("stop"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Escuchando la noche")
            .setContentText("Solo se guardan los momentos con ruido.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Terminar", stop).build())
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun listen(thr: Float, sens: String) {
        val out = File(dir(this), nightId).apply { mkdirs() }
        val meta = JSONObject()
            .put("id", nightId).put("start", startMs).put("end", startMs)
            .put("sens", sens).put("thr", thr.toDouble())
        val events = JSONArray()
        val counts = JSONObject().put("ronquido", 0).put("habla", 0).put("ruido", 0)
        var clips = 0
        var dropped = 0
        val fmt = SimpleDateFormat("HHmmss", Locale.US)

        val det = Detector(RATE, CHUNK, thr) { e ->
            counts.put(e.tipo, counts.optInt(e.tipo) + 1)
            count++
            if (clips >= MAX_CLIPS) {
                dropped++
            } else {
                val name = fmt.format(Date(e.t0)) + "_" + e.tipo + ".wav"
                writeWav(File(out, name), e.pcm, RATE)
                clips++
                events.put(
                    JSONObject().put("t", e.t0).put("tipo", e.tipo)
                        .put("dur", e.dur.toDouble()).put("peak", Math.round(e.peak)).put("file", name)
                )
            }
        }
        det.startMs = startMs

        fun save() {
            meta.put("end", System.currentTimeMillis())
            meta.put("counts", counts).put("events", events)
            meta.put("clips", clips).put("dropped", dropped)
            meta.put("floor", Math.round(det.floorDb()))
            meta.put("minutes", JSONArray(det.minutes.map { it.toDouble() }))
            File(out, "night.json").writeText(meta.toString())
        }
        save()

        val src = if (Build.VERSION.SDK_INT >= 24) MediaRecorder.AudioSource.VOICE_RECOGNITION else MediaRecorder.AudioSource.MIC
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(src, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, CHUNK * 8))
        rec.startRecording()
        var lastSave = System.currentTimeMillis()
        val buf = ShortArray(CHUNK)
        try {
            while (!stopFlag) {
                var n = 0
                while (n < CHUNK && !stopFlag) {
                    val r = rec.read(buf, n, CHUNK - n)
                    if (r <= 0) {
                        Thread.sleep(20)
                        continue
                    }
                    n += r
                }
                if (n < CHUNK) break
                det.process(buf.copyOf())
                level = det.lastOver
                val now = System.currentTimeMillis()
                if (now - lastSave > 60_000) {
                    lastSave = now
                    save()
                }
            }
        } finally {
            det.flush()
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            save()
        }
    }

    private fun writeWav(f: File, pcm: ShortArray, rate: Int) {
        val dataLen = pcm.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + dataLen); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1)
        bb.putInt(rate); bb.putInt(rate * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(dataLen)
        for (s in pcm) bb.putShort(s)
        FileOutputStream(f).use { it.write(bb.array()) }
    }

    private fun finish() {
        val id = nightId
        on = false
        level = 0f
        try { wake?.release() } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
        MainActivity.current?.send("nightDone", id)
    }

    override fun onDestroy() {
        stopFlag = true
        super.onDestroy()
    }
}
