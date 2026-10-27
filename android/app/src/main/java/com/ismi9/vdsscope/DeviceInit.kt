package com.ismi9.vdsscope

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Калібрування з factory-партиції флеша: int16 LE [канал][vb] */
class Calibration(val gain: Array<IntArray>, val step: Array<IntArray>, val comp: Array<IntArray>)

class DeviceInfo(
    val fpgaWasLoaded: Boolean,
    val version: String,
    val serial: String,
    val cal: Calibration?
)

/**
 * Старт приладу 1:1 з офіційним ПЗ (InterCommTiny / FPGADownloader / DeviceFlashCommunicator):
 *  1. FPGA_DOWNLOAD_QUERY(547): 0 = FPGA порожня
 *  2. read_flash(432): 2002 байти -> версія, серійник, калібрування
 *  3. якщо FPGA порожня -> завантажити .bin за версією
 *  4. конфігурація запису: DM, PRE/SUF_TRG, SAMPLE, EMPTY
 */
class DeviceInit(private val ctx: Context, private val usb: ScopeUsb) {

    var log: (String) -> Unit = {}

    fun run(): DeviceInfo? {
        val q = usb.sendCmd(VdsProtocol.ADD_FPGA_QUERY, 1, 0)
        if (q == null) { log("FPGA query: нема відповіді"); return null }
        val fpgaNotLoaded = q == 0
        log("FPGA: ${if (fpgaNotLoaded) "не завантажена" else "вже працює"}")

        val flash = readFlash() ?: run { log("Флеш: не прочитано"); return null }
        val reg = parseRegister(flash)
        val cal = parseCalibration(flash)
        log("Версія ${reg.first}, серійник ${reg.second}, калібр. ${if (cal != null) "OK" else "нема"}")

        if (fpgaNotLoaded) {
            val name = pickFpga(reg.first)
            log("Завантажую FPGA: $name")
            if (!downloadFpga(name)) { log("FPGA: помилка завантаження"); return null }
            log("FPGA: завантажено")
        }
        return DeviceInfo(fpgaNotLoaded, reg.first, reg.second, cal)
    }

    // ---------- Флеш ----------
    private fun readFlash(): ByteArray? {
        if (!usb.writeCmd(VdsProtocol.ADD_READ_FLASH, 1, 1)) return null
        val buf = usb.readExact(VdsProtocol.FLASH_SIZE, 3000) ?: return null
        if (buf[0] != 0xAA.toByte() || buf[1] != 0x55.toByte()) {
            log("Флеш: немає магії AA55 (${"%02x".format(buf[0])} ${"%02x".format(buf[1])})")
        }
        return buf
    }

    /** device-партиція починається з 6+1000 = 1006: [oem u8][version\0][serial\0]... */
    private fun parseRegister(flash: ByteArray): Pair<String, String> {
        var p = 6 + 1000
        p++ // oem
        fun str(): String {
            val sb = StringBuilder()
            var n = 0
            while (p < flash.size && flash[p] != 0.toByte() && n++ < 100) { sb.append((flash[p].toInt() and 0xFF).toChar()); p++ }
            p++
            return sb.toString()
        }
        val ver = str()
        val sn = str()
        return ver to sn
    }

    /** factory-партиція: 6..1005, три масиви int16 LE підряд: Gain, Step, Compensation, кожен [2][10] */
    private fun parseCalibration(flash: ByteArray): Calibration? {
        val bb = ByteBuffer.wrap(flash, 6, 1000).order(ByteOrder.LITTLE_ENDIAN)
        fun arr(): Array<IntArray> = Array(2) { IntArray(10) { val v = bb.short.toInt(); if (v <= 0) 0 else v } }
        return try { Calibration(arr(), arr(), arr()) } catch (e: Exception) { null }
    }

    // ---------- FPGA ----------
    private fun pickFpga(version: String): String {
        val v = version.uppercase()
        val tag = when {
            v == "V2.4.623" || v == "V2.6.0" -> "FPGAV2"
            v == "V2.7.0" || v == "V3.0.0" -> "FPGAV3"
            v == "V4.0.0" -> "FPGAV4"
            v == "V5.0.0" -> "FPGAV5"
            v.startsWith("V") && v.length in 4..10 &&
                (v.substring(1).replace(".", "").toIntOrNull() ?: 0) >= 500 -> "FPGAV5"
            else -> "FPGAV1"
        }
        val files = ctx.assets.list("fpga")?.toList().orEmpty()
        return files.firstOrNull { it.uppercase().contains(tag) } ?: files.first()
    }

    private fun downloadFpga(assetName: String): Boolean {
        val data = ctx.assets.open("fpga/$assetName").use { it.readBytes() }
        val hdr = ByteArray(9)
        VdsProtocol.writeIntLE(hdr, 0, VdsProtocol.ADD_FPGA_DOWNLOAD)
        hdr[4] = 4
        VdsProtocol.writeIntLE(hdr, 5, data.size)
        if (!usb.writeRaw(hdr)) return false
        val r = usb.readExact(5, 2000) ?: return false
        val mcuSize = VdsProtocol.readIntLE(r, 1)
        log("MCU buffer: $mcuSize")
        if (mcuSize <= 4 || mcuSize > 65536) return false
        val chunk = mcuSize - 4
        val frame = ByteArray(mcuSize)
        var p = 0; var n = 0; var fails = 0
        while (p < data.size) {
            val send = minOf(chunk, data.size - p)
            VdsProtocol.writeIntLE(frame, 0, n)
            System.arraycopy(data, p, frame, 4, send)
            if (send < chunk) java.util.Arrays.fill(frame, 4 + send, frame.size, 0)
            Thread.sleep(50)
            if (!usb.writeRaw(frame)) return false
            val ack = usb.readExact(5, 2000)
            if (ack == null || ack[0] != 83.toByte()) {
                if (++fails >= 5) return false
                Thread.sleep(100); continue
            }
            p += send; n++
        }
        return true
    }

    // ---------- Конфіг запису ----------
    /** Параметри як у Submitor2: c_dm, c_sample, c_htp, канали */
    fun configureAcquisition(info: DeviceInfo, ch1On: Boolean, ch2On: Boolean, vb1: Int, vb2: Int, tbIdx: Int) {
        usb.writeCmd(VdsProtocol.ADD_DM, 2, VdsProtocol.DM_LEN)           // 5000+50+50
        usb.writeCmd(VdsProtocol.ADD_SAMPLE, 1, 0)                          // не peak-detect
        val half = VdsProtocol.DM_LEN shr 1
        repeat(2) { usb.writeCmd(VdsProtocol.ADD_PRE_TRG, 2, half) }
        repeat(2) { usb.writeCmd(VdsProtocol.ADD_SUF_TRG, 4, half) }
        usb.writeCmd(VdsProtocol.ADD_EMPTY, 1, 1)

        // канали: прапор + gain + zero (калібровані)
        applyChannel(info, 0, ch1On, vb1)
        applyChannel(info, 1, ch2On, vb2)
        usb.writeCmd(VdsProtocol.ADD_CHL_ON, 1, (if (ch1On) 1 else 0) or (if (ch2On) 2 else 0))
        usb.writeCmd(VdsProtocol.ADD_EMPTY, 1, 0)

        // розгортка
        val div = VdsProtocol.SEPERATE_FREQ.getOrElse(tbIdx) { 1 }
        usb.writeCmd(VdsProtocol.ADD_TIMEBASE, 4, div)
        usb.writeCmd(VdsProtocol.ADD_SLOWMOVE, 1, if (tbIdx >= 22) 1 else 0) // >=100ms
        usb.writeCmd(VdsProtocol.ADD_EMPTY, 1, 1)
    }

    fun applyChannel(info: DeviceInfo, chl: Int, on: Boolean, vb: Int) {
        // прапор каналу: bit7=on, bits5-6=coupling(DC->1), bit1=1 якщо vb>5
        var flag = 0
        if (on) flag = flag or 0x80
        flag = flag or (1 shl 5)            // DC
        if (vb > 5) flag = flag or 0x02
        val chAdd = if (chl == 0) VdsProtocol.ADD_CHANNEL_CH1 else VdsProtocol.ADD_CHANNEL_CH2
        usb.writeCmd(chAdd, 1, flag)

        val cal = info.cal
        val gain = (cal?.gain?.get(chl)?.get(vb) ?: 0).coerceAtMost(4095)
        val zero = ((cal?.comp?.get(chl)?.get(vb) ?: 2048)).coerceIn(0, 4096)  // pos0 = 0
        val gAdd = if (chl == 0) VdsProtocol.ADD_GAIN_CH1 else VdsProtocol.ADD_GAIN_CH2
        val zAdd = if (chl == 0) VdsProtocol.ADD_ZERO_CH1 else VdsProtocol.ADD_ZERO_CH2
        usb.writeCmd(gAdd, 2, gain)
        usb.writeCmd(zAdd, 2, zero)
    }
}
