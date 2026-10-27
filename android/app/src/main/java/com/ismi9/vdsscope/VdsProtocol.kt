package com.ismi9.vdsscope

/** Протокол OWON VDS C2 (VDS1022/I). Див. docs/PROTOCOL.md */
object VdsProtocol {
    const val VID = 0x5345
    const val PID = 0x1234
    const val RESP_LEN = 5
    const val PACKET_LEN = 5211      // пакет кадру каналу
    const val DM_LEN = 5100
    const val TRG_LEN = 100
    const val HDR_LEN = 11

    // Адреси регістрів
    const val ADD_TRG_D = 1
    const val ADD_RE_COLLECT = 3
    const val ADD_CHL_ON = 11
    const val ADD_SLOWMOVE = 10
    const val ADD_MULTIFREQ = 80
    const val ADD_TIMEBASE = 82
    const val ADD_RUNSTOP = 97
    const val ADD_EDGE_LEVEL_CH1 = 46
    const val ADD_EDGE_LEVEL_CH2 = 48
    const val ADD_ZERO_CH1 = 266
    const val ADD_ZERO_CH2 = 264
    const val ADD_GAIN_CH1 = 278
    const val ADD_GAIN_CH2 = 276
    const val ADD_CHANNEL_CH1 = 273   // індекс чутливості 0..9
    const val ADD_CHANNEL_CH2 = 272
    const val ADD_GETDATA = 4096
    const val ADD_MACHINE_TYPE = 16385
    const val MACHINE_TYPE_VALUE = 86 // 'V'

    /** Профіль VDS1022: дільники TIMEBASE (32 кроки) */
    val SEPERATE_FREQ = intArrayOf(
        1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 4, 8, 20, 40, 80, 200,
        400, 800, 2000, 4000, 8000, 20000, 40000, 80000, 200000,
        400000, 800000, 2000000, 4000000, 8000000, 20000000, 40000000
    )

    val TIMEBASE_LABELS = arrayOf(
        "5 ns", "10 ns", "20 ns", "50 ns", "100 ns", "200 ns", "500 ns",
        "1 us", "2 us", "5 us", "10 us", "20 us", "50 us", "100 us",
        "200 us", "500 us", "1 ms", "2 ms", "5 ms", "10 ms", "20 ms",
        "50 ms", "100 ms", "200 ms", "500 ms", "1 s", "2 s", "5 s",
        "10 s", "20 s", "50 s", "100 s"
    )

    val VOLTBASE_LABELS = arrayOf(
        "5 mV", "10 mV", "20 mV", "50 mV", "100 mV", "200 mV",
        "500 mV", "1 V", "2 V", "5 V"
    )

    fun writeIntLE(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
        b[off + 2] = ((v shr 16) and 0xFF).toByte()
        b[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    fun readIntLE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    /** Пакет команди: [адреса u32 LE][len u8][значення len LE] */
    fun packCmd(add: Int, bytes: Int, value: Int): ByteArray {
        val buf = ByteArray(5 + bytes)
        writeIntLE(buf, 0, add)
        buf[4] = bytes.toByte()
        var v = value
        var p = 5
        repeat(bytes) {
            buf[p++] = (v and 0xFF).toByte()
            v = v shr 8
        }
        return buf
    }

    /** Значення з відповіді (байти 1..4) */
    fun respValue(resp: ByteArray): Int = readIntLE(resp, 1)

    /** Бітова карта каналів для GETDATA: обидва on -> 0x0505 */
    fun chlFlag(ch1On: Boolean, ch2On: Boolean): Int {
        var f = 0
        f = (f shl 8) or (if (ch2On) 5 else 4)
        f = (f shl 8) or (if (ch1On) 5 else 4)
        return f
    }
}
