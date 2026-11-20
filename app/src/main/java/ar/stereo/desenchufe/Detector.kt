package ar.stereo.desenchufe

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Escucha trozos de 100 ms. Aprende el ruido de fondo, detecta cuando algo
 * lo supera y arma un "momento" con 3 segundos previos incluidos.
 * Clasifica por dónde está la energía: grave = ronquido, voz = habla, resto = ruido.
 */
class Detector(
    private val rate: Int,
    private val chunk: Int,
    private val thr: Float,
    private val onEvent: (Event) -> Unit
) {
    class Event(
        val t0: Long,
        val pcm: ShortArray,
        val tipo: String,
        val dur: Float,
        val peak: Float
    )

    private val chunkDur = chunk.toFloat() / rate
    private val preN = Math.ceil(3.0 / chunkDur).toInt()
    private var floor: Float? = null
    private val ring = ArrayDeque<ShortArray>()
    private var hot = 0

    private var ev: Building? = null

    var lastOver = 0f
        private set
    val minutes = ArrayList<Float>()
    var startMs = System.currentTimeMillis()

    private class Building(val t0: Long) {
        val chunks = ArrayList<ShortArray>()
        var loudIdx = 0
        val bands = DoubleArray(3)
        val dbs = ArrayList<Float>()
        var peak = 0f
        var quiet = 0f
    }

    fun floorDb(): Float = floor ?: -90f

    fun process(x: ShortArray) {
        var sum = 0.0
        for (s in x) {
            val v = s / 32768.0
            sum += v * v
        }
        val db = (20 * log10(sqrt(sum / x.size) + 1e-9)).toFloat()
        val f = floor ?: db.also { floor = it }
        val over = db - f
        lastOver = over
        val now = System.currentTimeMillis()

        val mi = ((now - startMs) / 60000).toInt()
        while (minutes.size <= mi) minutes.add(0f)
        if (over > minutes[mi]) minutes[mi] = min(60f, Math.round(over * 10) / 10f)

        val cur = ev
        if (cur == null) {
            ring.addLast(x)
            if (ring.size > preN) ring.removeFirst()
            if (over > thr) {
                hot++
                if (hot >= 2) {
                    val b = Building(now - (chunkDur * 2000).toLong())
                    b.chunks.addAll(ring)
                    b.loudIdx = b.chunks.size - 1
                    b.dbs.add(over)
                    b.peak = over
                    addBands(b, x)
                    ring.clear()
                    ev = b
                }
            } else {
                hot = 0
                floor = if (db < f) f * 0.7f + db * 0.3f else f + (db - f) * 0.003f
            }
        } else {
            cur.chunks.add(x)
            if (over > thr - 4) {
                cur.quiet = 0f
                cur.loudIdx = cur.chunks.size - 1
                cur.dbs.add(over)
                if (over > cur.peak) cur.peak = over
                addBands(cur, x)
            } else {
                cur.quiet += chunkDur
            }
            val dur = (now - cur.t0) / 1000f
            if (cur.quiet >= 3f || dur >= 30f) close()
        }
    }

    fun flush() {
        if (ev != null) close()
    }

    private fun close() {
        val b = ev ?: return
        ev = null
        hot = 0
        val keep = min(b.chunks.size, b.loudIdx + 1 + Math.ceil(1.0 / chunkDur).toInt())
        var len = 0
        for (i in 0 until keep) len += b.chunks[i].size
        val pcm = ShortArray(len)
        var o = 0
        for (i in 0 until keep) {
            val c = b.chunks[i]
            System.arraycopy(c, 0, pcm, o, c.size)
            o += c.size
        }
        val dur = max(0.1f, (b.dbs.size + 1) * chunkDur)
        onEvent(Event(b.t0, pcm, classify(b, dur), Math.round(dur * 10) / 10f, b.peak))
    }

    private fun classify(b: Building, dur: Float): String {
        val tot = b.bands[0] + b.bands[1] + b.bands[2] + 1e-20
        val lo = b.bands[0] / tot
        val mi = b.bands[1] / tot
        val mean = b.dbs.average()
        var v = 0.0
        for (d in b.dbs) v += (d - mean) * (d - mean)
        val sd = sqrt(v / b.dbs.size)
        if (dur >= 0.6f && lo >= 0.6) return "ronquido"
        if (dur >= 0.8f && mi >= 0.35 && sd >= 3) return "habla"
        return "ruido"
    }

    private val n = 1024
    private val re = DoubleArray(n)
    private val im = DoubleArray(n)
    private val win = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }

    private fun addBands(b: Building, x: ShortArray) {
        val m = min(n, x.size)
        for (i in 0 until n) {
            re[i] = if (i < m) x[i] / 32768.0 * win[i] else 0.0
            im[i] = 0.0
        }
        fft(re, im)
        val hz = rate.toDouble() / n
        for (k in 1 until n / 2) {
            val fr = k * hz
            if (fr < 40) continue
            val p = re[k] * re[k] + im[k] * im[k]
            when {
                fr < 300 -> b.bands[0] += p
                fr < 3400 -> b.bands[1] += p
                fr < 8000 -> b.bands[2] += p
            }
        }
    }

    private fun fft(r: DoubleArray, i: DoubleArray) {
        val size = r.size
        var j = 0
        for (k in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (k < j) {
                var t = r[k]; r[k] = r[j]; r[j] = t
                t = i[k]; i[k] = i[j]; i[j] = t
            }
        }
        var len = 2
        while (len <= size) {
            val ang = -2 * PI / len
            val wr = cos(ang)
            val wi = kotlin.math.sin(ang)
            var s = 0
            while (s < size) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = s + k
                    val bIdx = a + len / 2
                    val tr = r[bIdx] * cr - i[bIdx] * ci
                    val ti = r[bIdx] * ci + i[bIdx] * cr
                    r[bIdx] = r[a] - tr
                    i[bIdx] = i[a] - ti
                    r[a] += tr
                    i[a] += ti
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                s += len
            }
            len = len shl 1
        }
    }
}
