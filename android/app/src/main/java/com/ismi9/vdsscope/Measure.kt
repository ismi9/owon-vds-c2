package com.ismi9.vdsscope

/**
 * Програмний тригер і вимірювання:
 *  - alignRise: зсув масиву семплів так, щоб перший піднімальний фронт
 *    стояв на 1/4 довжини кадру — стабільне зображення періодичного сигналу.
 *  - freqHz: частота за піднімальними переходами через середній рівень.
 *  - vppVolts: розмах у вольтах за шкалою екрана (125 кодів = 4 поділки).
 */
object Measure {

    /** "50 us" -> 50e-6 с */
    fun labelToSeconds(label: String): Double {
        val m = Regex("([\\d.]+)\\s*(ns|us|ms|s)").find(label) ?: return 0.0
        val v = m.groupValues[1].toDouble()
        return when (m.groupValues[2]) {
            "ns" -> v * 1e-9
            "us" -> v * 1e-6
            "ms" -> v * 1e-3
            else -> v
        }
    }

    /** "1 V" -> 1.0; "50 mV" -> 0.05 */
    fun labelToVolts(label: String): Double {
        val m = Regex("([\\d.]+)\\s*(mV|V)").find(label) ?: return 0.0
        val v = m.groupValues[1].toDouble()
        return if (m.groupValues[2] == "mV") v / 1000.0 else v
    }

    fun formatFreq(hz: Double?): String =
        if (hz == null || hz <= 0.0 || !hz.isFinite()) "--"
        else when {
            hz >= 1e6 -> "%.2f MHz".format(hz / 1e6)
            hz >= 1e3 -> "%.2f kHz".format(hz / 1e3)
            hz >= 1 -> "%.1f Hz".format(hz)
            else -> "%.1f mHz".format(hz * 1e3)
        }


    /** 2.5e6 -> "2.5MS/s" */
    fun formatSampleRate(hz: Double): String =
        when {
            hz >= 1e6 -> "%.2gMS/s".format(hz / 1e6)
            hz >= 1e3 -> "%.2gkS/s".format(hz / 1e3)
            else -> "%.2gS/s".format(hz)
        }

    /** Пороги (min, max, mid) сигналу */
    private fun midOf(s: ByteArray): Int {
        var mn = 127; var mx = -128
        for (b in s) {
            val v = b.toInt()
            if (v < mn) mn = v
            if (v > mx) mx = v
        }
        return (mn + mx) / 2
    }

    /**
     * Вікно відображення: глибина пам'яті приладу вдвічі довша за екран
     * (кадр ~5089 точок = 2 екрани), тож малюємо центральні N/2 точок.
     * Якщо є піднімальний фронт — він стає на 1/4 ширини вікна.
     */
    fun displayWindow(samples: ByteArray): ByteArray {
        val n = samples.size
        if (n < 128) return samples
        val mid = midOf(samples)
        var idx = -1
        for (i in 1 until n) {
            if (samples[i - 1].toInt() < mid && samples[i].toInt() >= mid) {
                idx = i; break
            }
        }
        val len = n / 2
        val start0 = if (idx >= 0) idx - len / 4 else n / 4
        val out = ByteArray(len)
        for (j in 0 until len) {
            var k = start0 + j
            if (k < 0) k += n
            if (k >= n) k -= n
            out[j] = samples[k]
        }
        return out
    }

    /**
     * Частота: піднімальні переходи через mid з гістерезисом.
     * Повертає null, якщо переходів менше двох (частоту не визначити).
     */
    fun freqHz(samples: ByteArray, tpp: Double): Double? {
        val n = samples.size
        if (n < 64 || tpp <= 0.0) return null
        val mid = midOf(samples).toDouble()
        var mn = 127; var mx = -128
        for (b in samples) {
            val v = b.toInt()
            if (v < mn) mn = v
            if (v > mx) mx = v
        }
        val hyst = (mx - mn) * 0.1
        if (hyst < 1.0) return null
        val lo = mid - hyst / 2
        val hi = mid + hyst / 2
        val crossings = ArrayList<Int>()
        var armed = true
        for (i in 0 until n) {
            val v = samples[i].toDouble()
            if (armed && v >= hi && i > 0 && samples[i - 1].toDouble() < hi) {
                crossings.add(i); armed = false
            } else if (!armed && v <= lo) {
                armed = true
            }
        }
        if (crossings.size < 2) return null
        val first = crossings.first()
        val last = crossings.last()
        val span = last - first
        if (span <= 0) return null
        return (crossings.size - 1) / (span * tpp)
    }

    /**
     * Розмах у вольтах: (max-min) кодів × 0.04 × V/div (формула MeasureADC офіційного ПЗ).
     */
    fun vppVolts(samples: ByteArray, voltPerDiv: Double): Double? {
        if (samples.isEmpty() || voltPerDiv <= 0.0) return null
        var mn = 127; var mx = -128
        for (b in samples) {
            val v = b.toInt()
            if (v < mn) mn = v
            if (v > mx) mx = v
        }
        return (mx - mn) * voltPerDiv * 0.04   // як ПК-ПЗ: Vconst = 0.04 * V/div (25 кодів на поділку)
    }
}
