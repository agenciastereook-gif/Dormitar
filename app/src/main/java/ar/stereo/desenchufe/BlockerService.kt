package ar.stereo.desenchufe

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Mira solamente qué app pasa al frente. Si es una de las que elegiste,
 * abre la pausa de Desenchufe antes de dejarte entrar.
 */
class BlockerService : AccessibilityService() {

    companion object {
        @Volatile var running = false
    }

    private var lastPkg = ""
    private var lastAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        running = false
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (!Prefs.blocked(this).contains(pkg)) return

        val p = Prefs.get(this)
        val now = System.currentTimeMillis()
        val inBlock = p.getLong("blockUntil", 0L) > now
        val hasPass = p.getLong("pass_$pkg", 0L) > now
        if (!inBlock && hasPass) return
        if (pkg == lastPkg && now - lastAt < 2000) return
        lastPkg = pkg
        lastAt = now

        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("intercept", pkg)
        startActivity(i)
    }

    override fun onInterrupt() {}
}
