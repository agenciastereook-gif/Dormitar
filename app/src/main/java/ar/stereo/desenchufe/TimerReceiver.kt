package ar.stereo.desenchufe

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Temporizador de apagado para lo que suene en otra app (YouTube, Spotify...).
 * Al vencer, Desenchufe pide el foco de audio: las apps de música y video se pausan solas.
 */
class TimerReceiver : BroadcastReceiver() {

    companion object {
        fun pending(ctx: Context): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, 41, Intent(ctx, TimerReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        fun schedule(ctx: Context, at: Long) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pending(ctx)
            val exactOk = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (exactOk) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            Prefs.get(ctx).edit().putLong("sleepAt", at).apply()
        }

        fun cancel(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pending(ctx))
            Prefs.get(ctx).edit().putLong("sleepAt", 0L).apply()
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Prefs.get(context).edit().putLong("sleepAt", 0L).apply()
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { }
            .build()
        am.requestAudioFocus(req)
        // También frena los sonidos propios de Desenchufe, si estaban sonando.
        try {
            context.startService(Intent(context, SoundService::class.java).setAction(SoundService.ACTION_STOP))
        } catch (_: Exception) {
        }
        val done = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            am.abandonAudioFocusRequest(req)
            done.finish()
        }, 4000)
        MainActivity.current?.send("sleepTimer", "done")
    }
}
