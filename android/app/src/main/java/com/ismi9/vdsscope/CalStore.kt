package com.ismi9.vdsscope

import android.content.Context

/**
 * Поправка «вольт на поділку» за еталонним джерелом.
 * Заводські таблиці (Gain/Ampl/Comp) задають аналоговий тракт приладу, але залишкова
 * похибка підсилювача на кожному діапазоні різна. Коефіцієнт тут множить виміряний
 * розмах, щоб цифра збіглася з еталоном: 1.0 = без поправки.
 */
class CalStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("vds_cal", Context.MODE_PRIVATE)

    fun factor(chl: Int): Double =
        prefs.getFloat(key(chl), 1.0f).toDouble().takeIf { it > 0.1 && it < 10.0 } ?: 1.0

    fun setFactor(chl: Int, v: Double) {
        prefs.edit().putFloat(key(chl), v.toFloat().coerceIn(0.5f, 2.0f)).apply()
    }

    fun reset() = prefs.edit().clear().apply()

    private fun key(chl: Int) = "factor_ch${chl + 1}"
}
