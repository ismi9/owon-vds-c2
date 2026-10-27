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
        val c = connection ?: return null
        val ep = epIn ?: return null
        val epO = epOut ?: return null
        val cmd = VdsProtocol.packCmd(
            VdsProtocol.ADD_GETDATA, 2, VdsProtocol.chlFlag(ch1On, ch2On)
        )
        if (c.bulkTransfer(epO, cmd, cmd.size, 1000) != cmd.size) return null
        val onCount = (if (ch1On) 1 else 0) + (if (ch2On) 1 else 0)
        val pkt = ByteArray(VdsProtocol.PACKET_LEN)
        val frames = ArrayList<ChannelFrame>(onCount)
        repeat(onCount) {
            var total = 0
            while (total < VdsProtocol.PACKET_LEN) {
                val n = c.bulkTransfer(ep, pkt, VdsProtocol.PACKET_LEN, 3000)
                if (n <= 0) return null
                // 'EBUSY' відповідається першим байтом 0x45 ('E')
                if (total == 0 && n >= 5 && pkt[0] == 0x45.toByte()) return null
                total += n
                if (total > VdsProtocol.PACKET_LEN) return null
            }
            frames.add(parsePacket(pkt))
        }
        return frames
    }

    /** Розбір пакету 5211: [11 hdr][100 trg][5100 dm] */
    private fun parsePacket(p: ByteArray): ChannelFrame {
        val dmOff = VdsProtocol.HDR_LEN + VdsProtocol.TRG_LEN
        val chl = p[dmOff].toInt()
        val timeSum = VdsProtocol.readIntLE(p, dmOff + 1)
        val peroidNum = VdsProtocol.readIntLE(p, dmOff + 5)
        val samples = p.copyOfRange(dmOff + 11, dmOff + 11 + 2500)
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
