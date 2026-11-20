package ar.stereo.desenchufe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import org.json.JSONObject
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** Lluvia, ruido marrón, ventilador y olas, generados en el celu. Siguen con la pantalla apagada. */
class SoundService : Service() {

    companion object {
        const val CH = "sonidos"
        const val NID = 12
        const val ACTION_STOP = "stop"
        const val SR = 44100

        @Volatile var kind = ""
        @Volatile var volume = 0.55f
        @Volatile var stopAt = 0L

        fun state(): String = JSONObject()
            .put("kind", kind).put("volume", volume.toDouble()).put("stopAt", stopAt).toString()
    }

    @Volatile private var gen = 0
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopAll(); return START_NOT_STICKY }
            "volume" -> { volume = intent.getFloatExtra("v", volume); return START_NOT_STICKY }
            "timer" -> {
                val m = intent.getIntExtra("min", 0)
                stopAt = if (m > 0) System.currentTimeMillis() + m * 60_000L else 0L
                return START_NOT_STICKY
            }
        }
        val k = intent?.getStringExtra("kind") ?: "lluvia"
        volume = intent?.getFloatExtra("v", volume) ?: volume
        val m = intent?.getIntExtra("min", 0) ?: 0
        stopAt = if (m > 0) System.currentTimeMillis() + m * 60_000L else 0L

        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(NotificationChannel(CH, "Sonidos para dormir", NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(
            this, 3, Intent(this, SoundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 4, Intent(this, MainActivity::class.java).putExtra("go", "dormir"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Sonando: " + label(k))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Apagar", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NID, n)

        val my = ++gen
        worker?.join(800)
        kind = k
        worker = Thread({ play(k, my) }, "sonido").also { it.start() }
        return START_NOT_STICKY
    }

    private fun label(k: String) = when (k) {
        "lluvia" -> "lluvia"
        "marron" -> "ruido marrón"
        "ventilador" -> "ventilador"
        "olas" -> "olas"
        else -> k
    }

    private fun play(k: String, my: Int) {
        val buf = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(SR)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setBufferSizeInBytes(maxOf(buf, 8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()

        val rnd = Random()
        var b0 = 0.0; var b1 = 0.0; var b2 = 0.0; var b3 = 0.0; var b4 = 0.0; var b5 = 0.0; var b6 = 0.0
        var brown = 0.0
        var lp1 = 0.0; var lp2 = 0.0; var hpPrev = 0.0; var hpOut = 0.0
        var phase = 0.0; var hum = 0.0
        var gain = 0.0
        val out = ShortArray(2048)

        fun lpCoef(fc: Double) = 1 - exp(-2 * PI * fc / SR)
        val cutoff = when (k) { "lluvia" -> 7000.0; "marron" -> 1100.0; "ventilador" -> 520.0; else -> 1300.0 }
        val a = lpCoef(cutoff)
        val hpA = if (k == "lluvia") exp(-2 * PI * 450.0 / SR) else 0.0

        var timerEnded = false
        while (gen == my) {
            val now = System.currentTimeMillis()
            val sa = stopAt
            var fade = 1.0
            if (sa > 0) {
                val left = sa - now
                if (left <= 0) { timerEnded = true; break }
                if (left < 30_000) fade = left / 30_000.0
            }
            val target = volume.toDouble().pow(1.6) * 0.9 * fade
            for (i in out.indices) {
                val w = rnd.nextGaussian() * 0.33
                val s: Double = when (k) {
                    "marron", "ventilador" -> {
                        brown = (brown + 0.02 * w) / 1.02
                        brown * 3.5
                    }
                    else -> {
                        b0 = 0.99886 * b0 + w * 0.0555179; b1 = 0.99332 * b1 + w * 0.0750759
                        b2 = 0.969 * b2 + w * 0.153852; b3 = 0.8665 * b3 + w * 0.3104856
                        b4 = 0.55 * b4 + w * 0.5329522; b5 = -0.7616 * b5 - w * 0.016898
                        val p = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362) * 0.11
                        b6 = w * 0.115926
                        p
                    }
                }
                lp1 += a * (s - lp1)
                lp2 += a * (lp1 - lp2)
                var y = lp2
                if (hpA > 0) {
                    hpOut = hpA * (hpOut + y - hpPrev)
                    hpPrev = y
                    y = hpOut * 1.6
                }
                if (k == "ventilador") {
                    hum += 2 * PI * 97.0 / SR
                    y = y * 1.4 + sin(hum) * 0.012
                }
                if (k == "olas") {
                    phase += 2 * PI * 0.075 / SR
                    y *= 0.5 + 0.46 * sin(phase)
                    y *= 1.8
                }
                gain += (target - gain) * 0.00005
                val v = (y * gain * 32767).coerceIn(-32767.0, 32767.0)
                out[i] = v.toInt().toShort()
            }
            track.write(out, 0, out.size)
        }
        try { track.stop() } catch (_: Exception) {}
        track.release()
        if (timerEnded && gen == my) stopAll()
    }

    private fun stopAll() {
        gen++
        kind = ""
        stopAt = 0L
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
        MainActivity.current?.send("sound", "stopped")
    }

    override fun onDestroy() {
        gen++
        super.onDestroy()
    }
}
