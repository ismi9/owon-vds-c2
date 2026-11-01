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
    /** 1e8 -> "100 MS/s", 2.5e6 -> "2.5 MS/s", 500.0 -> "500 S/s" (без експоненти) */
    fun formatSampleRate(hz: Double): String {
        fun fmt(v: Double): String =
            if (v >= 100 || Math.abs(v - Math.round(v)) < 0.05) "%d".format(Math.round(v))
            else "%.1f".format(v)
        return when {
            hz >= 1e6 -> fmt(hz / 1e6) + " MS/s"
            hz >= 1e3 -> fmt(hz / 1e3) + " kS/s"
            else -> fmt(hz) + " S/s"
        }
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

    /** Скільки семплів влазить на екран (10 поділок). Прилад віддає 5000 = 2 екрани. */
    fun windowLen(n: Int): Int = n / 2

    /**
     * Вікно відображення з апаратним вирівнюванням за cursor.
     * cursor — поле з DM-заголовка: позиція тригера, рахуючи від правого краю.
     * Тобто індекс тригера від лівого краю = n - cursor.
     * Тригер ставимо на 1/4 ширини вікна (2.5 поділки від лівого краю).
     * Якщо cursor некоректний (0 = тригера не було) — програмний пошук фронту.
     */
    fun displayWindow(samples: ByteArray, cursorFromRight: Int = 0, fall: Boolean = false): ByteArray {
        val n = samples.size
        if (n < 128) return samples
        val len = windowLen(n)
        // індекс тригера від лівого краю
        var trig = -1
        if (cursorFromRight in 1 until n) trig = n - cursorFromRight
        if (trig < 0) trig = findEdge(samples, fall)          // запасний варіант
        val start0 = if (trig >= 0) (trig - len / 4) else (n - len) / 2
        val start = start0.coerceIn(0, n - len)
        return samples.copyOfRange(start, start + len)
    }

    /** Індекс першого фронту (перехід через середину) або -1 */
    private fun findEdge(samples: ByteArray, fall: Boolean): Int {
        val n = samples.size
        val mid = midOf(samples)
        for (i in 1 until n) {
            val prev = samples[i - 1].toInt()
            val cur = samples[i].toInt()
            if (!fall && prev < mid && cur >= mid) return i
            if (fall && prev > mid && cur <= mid) return i
        }
        return -1
    }

    /**
     * Апаратний частотомір приладу: f = period_num / time_sum × 100 МГц.
     * time_sum = 0 означає, що прилад частоту не рахував.
     */
    fun hardwareFreqHz(timeSum: Int, peroidNum: Int, tpp: Double = 0.0): Double? {
        if (timeSum == 0 || peroidNum <= 0) return null
        val f = peroidNum.toDouble() / timeSum.toDouble() * 1e8
        if (!f.isFinite() || f <= 0.0) return null
        // вище за Найквіста частота виміряна бути не може — це сміття частотоміра
        if (tpp > 0.0 && f > 0.5 / tpp) return null
        return f
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
