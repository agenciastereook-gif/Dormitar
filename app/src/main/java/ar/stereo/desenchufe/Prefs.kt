package ar.stereo.desenchufe

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

object Prefs {
    const val NAME = "desenchufe"

    fun get(ctx: Context): SharedPreferences = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun blocked(ctx: Context): Set<String> {
        val out = HashSet<String>()
        try {
            val arr = JSONArray(get(ctx).getString("blocked", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o != null) out.add(o.optString("pkg")) else out.add(arr.optString(i))
            }
        } catch (_: Exception) {
        }
        return out
    }
}
