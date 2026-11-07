package com.ismi9.vdsscope

import android.app.PendingIntent
import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build

/** Кадр каналу: сирі 8-бітні семпли АЦП */
data class ChannelFrame(
    val chl: Int,
    val timeSum: Int,
    val peroidNum: Int,
    /** Позиція тригера в кадрі (0..5000), рахуючи від правого краю. 0 = тригера немає. */
    val cursor: Int,
    val samples: ByteArray,
    /** Діагностика: розібрані заголовки з зсуву 0 і з зсуву 111, у десяткових числах */
    val dbgHdr: String = "",
    val dbgDm: String = ""
)

class ScopeUsb(private val ctx: Context) {
    val usbManager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var usbIf: UsbInterface? = null
    private var epOut: UsbEndpoint? = null
    private var epIn: UsbEndpoint? = null
    var connectedDevice: UsbDevice? = null
        private set
    @Volatile var lastTrg = 0
    @Volatile var dbg = ""
    @Volatile var lastFin = 0

    fun findDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull {
            it.vendorId == VdsProtocol.VID && it.productId == VdsProtocol.PID
        }

    fun hasPermission(dev: UsbDevice): Boolean = usbManager.hasPermission(dev)

    fun requestPermission(dev: UsbDevice, onGranted: () -> Unit) {
        val pi = PendingIntent.getBroadcast(
            ctx, 0,
            android.content.Intent(ACTION_USB_PERMISSION).setPackage(ctx.packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        UsbOnGranted.listener = onGranted
        ctx.registerReceiver(UsbOnGranted, android.content.IntentFilter(ACTION_USB_PERMISSION))
        usbManager.requestPermission(dev, pi)
    }

    fun connect(dev: UsbDevice): Boolean {
        val conn = usbManager.openDevice(dev) ?: return false
        val iface = (0 until dev.interfaceCount)
            .map { dev.getInterface(it) }
            .firstOrNull { it.endpointCount >= 2 } ?: run {
            conn.close(); return false
        }
        if (!conn.claimInterface(iface, true)) { conn.close(); return false }
        epOut = (0 until iface.endpointCount).map { iface.getEndpoint(it) }
            .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }
        epIn = (0 until iface.endpointCount).map { iface.getEndpoint(it) }
            .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN }
        if (epOut == null || epIn == null) { conn.close(); return false }
        connection = conn
        usbIf = iface
        connectedDevice = dev
        return true
    }

    fun disconnect() {
        stopPolling()
        connection?.let { c -> usbIf?.let { c.releaseInterface(it) }; c.close() }
        connection = null; epIn = null; epOut = null; connectedDevice = null
    }

    val isConnected: Boolean get() = connection != null

    /** Потік опитування кадрів (належить USB-шару, щоб пережити Activity) */
    @Volatile var pollThread: Thread? = null
    val isPolling: Boolean get() = pollThread?.isAlive == true

    /** Зупиняє потік опитування і чекає його завершення (до 1.5с), перш ніж чіпати USB */
    fun stopPolling() {
        val t = pollThread ?: return
        pollThread = null
        try { t.join(1500) } catch (_: InterruptedException) {}
    }

    /** Лише запис, без читання відповіді (для GETDATA тощо) */
    @Synchronized fun writeCmdNoAck(add: Int, bytes: Int, value: Int): Boolean {
        val c = connection ?: return false
        val ep = epOut ?: return false
        val cmd = VdsProtocol.packCmd(add, bytes, value)
        return c.bulkTransfer(ep, cmd, cmd.size, 1000) == cmd.size
    }

    /** Команда запису + обов'язкове читання 5-байтової відповіді, як Sendable/JobUnit_I */
    @Synchronized fun writeCmd(add: Int, bytes: Int, value: Int): Boolean {
        if (!writeCmdNoAck(add, bytes, value)) return false
        return readResponse() != null
    }

    /** sendCMDbyBytes: значення розбивається на окремі 1-байтові команди з адресами add, add+1, ... */
    @Synchronized fun writeCmdBytes(add: Int, bytes: Int, value: Int): Boolean {
        var ok = true
        for (i in 0 until bytes) {
            val v = (value ushr (i shl 3)) and 0xFF
            if (!writeCmd(add + i, 1, v)) ok = false
        }
        return ok
    }

    /** Скинути залишки відповідей з IN-ендпоінта */
    @Synchronized fun drain() {
        val c = connection ?: return
        val ep = epIn ?: return
        val tmp = ByteArray(65536)
        var guard = 0
        while (guard++ < 8) {
            val n = c.bulkTransfer(ep, tmp, tmp.size, 20)
            if (n <= 0) break
        }
    }

    /** Сирий запис довільного буфера (OUT) */
    @Synchronized fun writeRaw(data: ByteArray): Boolean {
        val c = connection ?: return false
        val ep = epOut ?: return false
        var off = 0
        while (off < data.size) {
            val chunk = minOf(data.size - off, 16384)
            val n = c.bulkTransfer(ep, data, off, chunk, 2000)
            if (n <= 0) return false
            off += n
        }
        return true
    }

    /** Читає рівно len байтів з IN (кілька транзакцій) */
    @Synchronized fun readExact(len: Int, timeout: Int): ByteArray? {
        val c = connection ?: return null
        val ep = epIn ?: return null
        val out = ByteArray(len)
        var got = 0
        val tmp = ByteArray(maxOf(ep.maxPacketSize, 512) * 8)
        val deadline = System.currentTimeMillis() + timeout
        while (got < len && System.currentTimeMillis() < deadline) {
            val n = c.bulkTransfer(ep, tmp, minOf(tmp.size, len - got).coerceAtLeast(1), 500)
            if (n > 0) { System.arraycopy(tmp, 0, out, got, minOf(n, len - got)); got += n }
        }
        return if (got >= len) out else null
    }

    /** Читання відповіді 5 байтів; null = таймаут/помилка */
    @Synchronized fun readResponse(): Int? {
        val c = connection ?: return null
        val ep = epIn ?: return null
        val buf = ByteArray(VdsProtocol.RESP_LEN)
        val n = c.bulkTransfer(ep, buf, buf.size, 1000)
        if (n < VdsProtocol.RESP_LEN) return null
        return VdsProtocol.respValue(buf)
    }

    /** Команда + відповідь */
    @Synchronized fun sendCmd(add: Int, bytes: Int, value: Int): Int? {
        if (!writeCmdNoAck(add, bytes, value)) return null
        return readResponse()
    }

    /** Запит типу машини: >0 — прилад живий */
    fun queryMachineType(): Int? =
        sendCmd(VdsProtocol.ADD_MACHINE_TYPE, 1, VdsProtocol.MACHINE_TYPE_VALUE)

    /** Читання кадру: n каналових пакетів. Повертає null при EBUSY. */
    /**
     * Рівно потік офіційного ПЗ (TrgStatusInvestigator + GetDataRunner2):
     * TRG_D -> datafinished -> (trg!=0 || fin>0) ? GETDATA : нічого.
     * Жодних скидань буфера: запізніла відповідь споживається наступним читанням.
     */
    /**
     * Читання кадру.
     * pollStatus=false (Auto-sweep): статус не опитуємо взагалі — як офіційне ПЗ.
     * Прилад відповідає 5 байтами 'E' (EBUSY), якщо дані ще не готові; тоді null.
     * pollStatus=true (ONCE/NORMAL): спершу TRG_D і datafinished, як у ПК-ПЗ.
     */
    @Synchronized fun readFrame(ch1On: Boolean, ch2On: Boolean, pollStatus: Boolean = false): List<ChannelFrame>? {
        val c = connection ?: run { dbg = "нема connection"; return null }
        val ep = epIn ?: run { dbg = "нема epIn"; return null }
        val epO = epOut ?: run { dbg = "нема epOut"; return null }
        if (pollStatus) {
            val trg = sendCmd(VdsProtocol.ADD_TRG_D, 1, 0)
            if (trg == null) { dbg = "TRG_D нема"; return null }
            val fin = sendCmd(VdsProtocol.ADD_DATAFINISHED, 1, 0)
            if (fin == null) { dbg = "FIN нема"; return null }
            lastTrg = trg; lastFin = fin
            if (trg == 0 && fin <= 0) { dbg = "дані не готові (T=0 F=0)"; return null }
        }
        val cmd = VdsProtocol.packCmd(VdsProtocol.ADD_GETDATA, 2, VdsProtocol.chlFlag(ch1On, ch2On))
        if (c.bulkTransfer(epO, cmd, cmd.size, 1000) != cmd.size) { dbg = "GETDATA: запис не вдався"; return null }
        val onCount = (if (ch1On) 1 else 0) + (if (ch2On) 1 else 0)
        val frames = ArrayList<ChannelFrame>(onCount)
        val buf = ByteArray(65536)
        repeat(onCount) { idx ->
            val pkt = ByteArray(VdsProtocol.PACKET_LEN)
            var total = 0
            while (total < VdsProtocol.PACKET_LEN) {
                val tmo = if (total == 0) 3000 else 1000
                val n = c.bulkTransfer(ep, buf, buf.size, tmo)
                if (n <= 0) { dbg = "пак$idx таймаут=$tmo отримано=$total"; return null }
                if (total == 0 && n == 5 && buf[0] == 0x45.toByte()) { dbg = "EBUSY"; return null }
                val take = minOf(n, VdsProtocol.PACKET_LEN - total)
                System.arraycopy(buf, 0, pkt, total, take)
                total += take
            }
            frames.add(parsePacket(pkt))
        }
        dbg = "ok"
        return frames
    }

    /** Розбір пакету 5211: [11 hdr][100 trg][5100 dm] */
    /** "chl=.. t=.. p=.. c=.." для 11 байтів заголовка з зсуву off */
    private fun hdr(p: ByteArray, off: Int): String {
        val chl = p[off].toInt() and 0xFF
        val t = VdsProtocol.readIntLE(p, off + 1)
        val pn = VdsProtocol.readIntLE(p, off + 5)
        val c = (p[off + 9].toInt() and 0xFF) or ((p[off + 10].toInt() and 0xFF) shl 8)
        return "k=$chl t=$t p=$pn c=$c"
    }

    private fun parsePacket(p: ByteArray): ChannelFrame {
        // Розкладка пакета (5211 байт): [0..10] заголовок, [11..110] тригер-буфер,
        // [111..5210] ADC-блок (50 pre + 5000 + 50 post).
        // У v0.4.0 заголовок помилково читався з +111 (там уже семпли).
        val all = p.copyOfRange(111, 111 + VdsProtocol.DM_LEN)
        val samples = all.copyOfRange(50, all.size - 50)
        return ChannelFrame(
            chl = p[0].toInt() and 0xFF,
            timeSum = VdsProtocol.readIntLE(p, 1),
            peroidNum = VdsProtocol.readIntLE(p, 5),
            cursor = (p[9].toInt() and 0xFF) or ((p[10].toInt() and 0xFF) shl 8),
            samples = samples,
            dbgHdr = hdr(p, 0),
            dbgDm = hdr(p, 111)
        )
    }

    object UsbOnGranted : android.content.BroadcastReceiver() {
        var listener: (() -> Unit)? = null
        override fun onReceive(context: Context, intent: android.content.Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted) listener?.invoke()
                listener = null
            }
        }
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.ismi9.vdsscope.USB_PERMISSION"
    }
}
