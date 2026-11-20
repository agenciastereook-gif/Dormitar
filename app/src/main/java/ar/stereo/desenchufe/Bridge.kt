package ar.stereo.desenchufe

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.WindowManager
import android.webkit.JavascriptInterface
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Lo que la interfaz (HTML) le puede pedir al celu. */
class Bridge(private val a: MainActivity) {

    private val ctx: Context = a.applicationContext
    private val prefs get() = Prefs.get(ctx)

    @JavascriptInterface fun version(): String =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }

    @JavascriptInterface fun takePending(): String {
        val o = JSONObject()
        a.pendingShare?.let { o.put("share", it) }
        a.pendingIntercept?.let { o.put("intercept", it).put("interceptLabel", label(it)) }
        a.pendingGo?.let { o.put("go", it) }
        a.pendingShare = null
        a.pendingIntercept = null
        a.pendingGo = null
        return o.toString()
    }

    // ---------- Apps y bloqueo ----------

    @JavascriptInterface fun apps(): String {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val seen = HashSet<String>()
        val list = pm.queryIntentActivities(i, 0)
            .mapNotNull {
                val p = it.activityInfo.packageName
                if (p == ctx.packageName || !seen.add(p)) null
                else Pair(p, it.loadLabel(pm).toString())
            }
            .sortedBy { it.second.lowercase() }
        val arr = JSONArray()
        for ((p, l) in list) arr.put(JSONObject().put("pkg", p).put("label", l))
        return arr.toString()
    }

    @JavascriptInterface fun label(pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    @JavascriptInterface fun getBlocked(): String = prefs.getString("blocked", "[]") ?: "[]"
    @JavascriptInterface fun setBlocked(json: String) { prefs.edit().putString("blocked", json).apply() }

    @JavascriptInterface fun blockerOn(): Boolean {
        if (BlockerService.running) return true
        val flat = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(ctx, BlockerService::class.java).flattenToString()
        return flat.split(':').any { it.equals(me, ignoreCase = true) }
    }

    @JavascriptInterface fun openBlockerSettings() {
        a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @JavascriptInterface fun openAppInfo() {
        a.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    @JavascriptInterface fun grantPass(pkg: String, minutes: Int) {
        prefs.edit().putLong("pass_$pkg", System.currentTimeMillis() + minutes * 60_000L).apply()
    }

    @JavascriptInterface fun launchApp(pkg: String) {
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            a.startActivity(it)
        }
    }

    @JavascriptInterface fun goHome() {
        a.startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    @JavascriptInterface fun setBlockUntil(ts: String) {
        prefs.edit().putLong("blockUntil", ts.toDoubleOrNull()?.toLong() ?: 0L).apply()
    }

    @JavascriptInterface fun blockUntil(): String = prefs.getLong("blockUntil", 0L).toString()

    // ---------- Noche ----------

    @JavascriptInterface fun hasMic(): Boolean =
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @JavascriptInterface fun askPermissions() { a.runOnUiThread { a.askPermissions() } }

    @JavascriptInterface fun startNight(thr: String, sens: String) {
        val i = Intent(ctx, NightService::class.java)
            .putExtra("thr", thr.toFloatOrNull() ?: 12f)
            .putExtra("sens", sens)
        ctx.startForegroundService(i)
    }

    @JavascriptInterface fun stopNight() {
        ctx.startService(Intent(ctx, NightService::class.java).setAction("stop"))
    }

    @JavascriptInterface fun nightLive(): String = NightService.live()

    @JavascriptInterface fun nights(): String {
        val arr = JSONArray()
        val dirs = NightService.dir(ctx).listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name } ?: emptyList()
        for (d in dirs) {
            val f = File(d, "night.json")
            if (!f.exists()) continue
            try {
                val o = JSONObject(f.readText())
                o.remove("events"); o.remove("minutes")
                arr.put(o)
            } catch (_: Exception) {}
        }
        return arr.toString()
    }

    @JavascriptInterface fun night(id: String): String {
        val f = File(File(NightService.dir(ctx), safe(id)), "night.json")
        return if (f.exists()) f.readText() else "{}"
    }

    @JavascriptInterface fun deleteNight(id: String) {
        if (NightService.on && NightService.nightId == id) return
        File(NightService.dir(ctx), safe(id)).deleteRecursively()
    }

    @JavascriptInterface fun shareClip(id: String, file: String) {
        val f = File(File(NightService.dir(ctx), safe(id)), safe(file))
        if (!f.exists()) return
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND).setType("audio/wav")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        a.startActivity(Intent.createChooser(send, "Compartir audio").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun safe(s: String) = s.replace("/", "").replace("..", "")

    @JavascriptInterface fun batteryFree(): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    @JavascriptInterface fun askBatteryFree() {
        try {
            a.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            a.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // ---------- Sonidos ----------

    @JavascriptInterface fun playSound(kind: String, vol: String, minutes: Int) {
        val i = Intent(ctx, SoundService::class.java)
            .putExtra("kind", kind)
            .putExtra("v", vol.toFloatOrNull() ?: 0.55f)
            .putExtra("min", minutes)
        ctx.startForegroundService(i)
    }

    @JavascriptInterface fun stopSound() {
        if (SoundService.kind.isNotEmpty())
            ctx.startService(Intent(ctx, SoundService::class.java).setAction(SoundService.ACTION_STOP))
    }

    @JavascriptInterface fun setVolume(vol: String) {
        SoundService.volume = vol.toFloatOrNull() ?: SoundService.volume
    }

    @JavascriptInterface fun setSoundTimer(minutes: Int) {
        SoundService.stopAt = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
    }

    @JavascriptInterface fun soundState(): String = SoundService.state()

    // ---------- Temporizador para otras apps (YouTube, Spotify) ----------

    @JavascriptInterface fun setSleepTimer(minutes: Int) {
        if (minutes <= 0) TimerReceiver.cancel(ctx)
        else TimerReceiver.schedule(ctx, System.currentTimeMillis() + minutes * 60_000L)
    }

    @JavascriptInterface fun sleepTimerAt(): String = prefs.getLong("sleepAt", 0L).toString()

    @JavascriptInterface fun openUrl(url: String) {
        a.openExternal(Uri.parse(url))
    }

    // ---------- Pantalla y vibración ----------

    @JavascriptInterface fun keepScreenOn(on: Boolean) {
        a.runOnUiThread {
            if (on) a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else a.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @JavascriptInterface fun dim(on: Boolean) {
        a.runOnUiThread {
            val lp = a.window.attributes
            lp.screenBrightness = if (on) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            a.window.attributes = lp
        }
    }

    @JavascriptInterface fun vibrate(ms: Int) {
        try {
            val v = ctx.getSystemService(Vibrator::class.java)
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }
}
