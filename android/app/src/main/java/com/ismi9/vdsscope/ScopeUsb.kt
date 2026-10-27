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
data class ChannelFrame(val chl: Int, val timeSum: Int, val peroidNum: Int, val samples: ByteArray)

class ScopeUsb(private val ctx: Context) {
    private val usbManager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
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
        connection?.let { c -> usbIf?.let { c.releaseInterface(it) }; c.close() }
        connection = null; epIn = null; epOut = null; connectedDevice = null
    }

    val isConnected: Boolean get() = connection != null

    fun writeCmd(add: Int, bytes: Int, value: Int): Boolean {
        val c = connection ?: return false
        val ep = epOut ?: return false
        val cmd = VdsProtocol.packCmd(add, bytes, value)
        return c.bulkTransfer(ep, cmd, cmd.size, 1000) == cmd.size
    }

    /** Сирий запис довільного буфера (OUT) */
    fun writeRaw(data: ByteArray): Boolean {
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
    fun readExact(len: Int, timeout: Int): ByteArray? {
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
    fun readResponse(): Int? {
        val c = connection ?: return null
        val ep = epIn ?: return null
        val buf = ByteArray(VdsProtocol.RESP_LEN)
        val n = c.bulkTransfer(ep, buf, buf.size, 1000)
        if (n < VdsProtocol.RESP_LEN) return null
        return VdsProtocol.respValue(buf)
    }

    /** Команда + відповідь */
    fun sendCmd(add: Int, bytes: Int, value: Int): Int? {
        if (!writeCmd(add, bytes, value)) return null
        return readResponse()
    }

    /** Запит типу машини: >0 — прилад живий */
    fun queryMachineType(): Int? =
        sendCmd(VdsProtocol.ADD_MACHINE_TYPE, 1, VdsProtocol.MACHINE_TYPE_VALUE)

    /** Читання кадру: n каналових пакетів. Повертає null при EBUSY. */
    fun readFrame(ch1On: Boolean, ch2On: Boolean): List<ChannelFrame>? {
        val c = connection ?: run { dbg = "нема connection"; return null }
        val ep = epIn ?: run { dbg = "нема epIn"; return null }
        val epO = epOut ?: run { dbg = "нема epOut"; return null }
        val trg = sendCmd(VdsProtocol.ADD_TRG_D, 1, 0)
        if (trg == null) { dbg = "TRG_D: нема відповіді"; return null }
        val fin = sendCmd(VdsProtocol.ADD_DATAFINISHED, 1, 0)
        if (fin == null) { dbg = "FIN: нема відповіді"; return null }
        lastTrg = trg; lastFin = fin
        val cmd = VdsProtocol.packCmd(VdsProtocol.ADD_GETDATA, 2, VdsProtocol.chlFlag(ch1On, ch2On))
        if (c.bulkTransfer(epO, cmd, cmd.size, 1000) != cmd.size) { dbg = "GETDATA: запис не вдався"; return null }
        val onCount = (if (ch1On) 1 else 0) + (if (ch2On) 1 else 0)
        val frames = ArrayList<ChannelFrame>(onCount)
        val buf = ByteArray(65536)
        repeat(onCount) { idx ->
            val pkt = ByteArray(VdsProtocol.PACKET_LEN)
            var total = 0
            while (total < VdsProtocol.PACKET_LEN) {
                val n = c.bulkTransfer(ep, buf, buf.size, 2000)
                if (n <= 0) { dbg = "пакет$idx: таймаут n=$n отримано=$total"; return null }
                if (total == 0 && n == 5 && buf[0] == 0x45.toByte()) { dbg = "EBUSY (E)"; return null }
                if (total == 0 && n < VdsProtocol.PACKET_LEN) {
                    dbg = "пакет$idx: перша частина n=$n b0=${"%02x".format(buf[0])}"
                }
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
    private fun parsePacket(p: ByteArray): ChannelFrame {
        val dmOff = VdsProtocol.HDR_LEN + VdsProtocol.TRG_LEN
        val chl = p[dmOff].toInt()
        val timeSum = VdsProtocol.readIntLE(p, dmOff + 1)
        val peroidNum = VdsProtocol.readIntLE(p, dmOff + 5)
        // DM-блок: [chl u8][time_sum u32][peroid u32][slow u16] = 11 байт заголовка,
        // далі семпли; офіційне ПЗ відкидає по 50 байтів з країв (trimNgetADCByteBuffer)
        val all = p.copyOfRange(dmOff, dmOff + VdsProtocol.DM_LEN)
        val samples = all.copyOfRange(50, all.size - 50)
        return ChannelFrame(chl, timeSum, peroidNum, samples)
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
