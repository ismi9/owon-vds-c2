package com.ismi9.vdsscope

import android.app.Application

/**
 * Власник USB-з'єднання. Activity можуть перестворюватись (поворот, зміна теми,
 * системне вбивання у фоні), а з'єднання з приладом і його стан мають жити довше за них.
 */
class VdsApp : Application() {
    val scope: ScopeUsb by lazy { ScopeUsb(applicationContext) }

    /** Інформація про прилад після ініціалізації FPGA (щоб нова Activity не ініціалізувала знову) */
    @Volatile var devInfo: DeviceInfo? = null
}
