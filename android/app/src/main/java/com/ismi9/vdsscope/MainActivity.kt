package com.ismi9.vdsscope

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var scope: ScopeUsb
    private lateinit var scopeView: ScopeView
    private lateinit var status: TextView

    private lateinit var cbCh1: CheckBox
    private lateinit var cbCh2: CheckBox
    private lateinit var spTimebase: Spinner
    private lateinit var spTimebase2: Spinner
    private lateinit var spVolt1: Spinner
    private lateinit var spVolt2: Spinner
    private lateinit var btnRun: Button

    @Volatile private var running = false
    private var pollThread: Thread? = null
    private var framesCount = 0
    private var devInfo: DeviceInfo? = null
    private val usbExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private lateinit var init: DeviceInit

    private val ACTION_USB_PERMISSION = "com.ismi9.vdsscope.USB_PERMISSION"

    /** Живий стан USB-шини одним рядком */
    private fun usbBusStatus(): String {
        val all = scope.usbManager.deviceList.values
        val dev = all.firstOrNull { it.vendorId == VdsProtocol.VID && it.productId == VdsProtocol.PID }
        return if (dev == null) {
            "USB: " + (if (all.isEmpty()) "порожньо (прилад не бачать)" else
                all.joinToString(", ") { "%04x:%04x".format(it.vendorId, it.productId) })
        } else {
            "Прилад 5345:1234 є, " + (if (scope.usbManager.hasPermission(dev)) "дозвіл OK" else "без дозволу")
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    status.text = usbBusStatus()
                    // Дамо MCU приладу 1.5с на старт після подачі живлення
                    Thread {
                        Thread.sleep(1500)
                        runOnUiThread {
                            val dev = scope.findDevice()
                            if (dev != null && !scope.isConnected) {
                                if (scope.hasPermission(dev)) connect() else scope.requestPermission(dev) { connect() }
                            }
                        }
                    }.start()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    running = false; scope.disconnect()
                    status.text = "Прилад від'єднано"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        scope = ScopeUsb(this)
        init = DeviceInit(this, scope)
        init.log = { m -> runOnUiThread { status.text = m } }
        scopeView = findViewById(R.id.scopeView)
        status = findViewById(R.id.status)
        cbCh1 = findViewById(R.id.cbCh1)
        cbCh2 = findViewById(R.id.cbCh2)
        spTimebase = findViewById(R.id.spTimebase)
        spTimebase2 = findViewById(R.id.spTimebase2)
        spVolt1 = findViewById(R.id.spVolt1)
        spVolt2 = findViewById(R.id.spVolt2)
        btnRun = findViewById(R.id.btnRun)

        spTimebase.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            VdsProtocol.TIMEBASE_LABELS.toList()
        )
        spTimebase2.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            VdsProtocol.TIMEBASE_LABELS.toList()
        )
        spVolt1.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            VdsProtocol.VOLTBASE_LABELS.toList()
        )
        spVolt2.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            VdsProtocol.VOLTBASE_LABELS.toList()
        )

        findViewById<Button>(R.id.btnConnect).setOnClickListener { connect() }
        btnRun.setOnClickListener { toggleRun() }

        registerReceiver(usbReceiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        })
        status.text = usbBusStatus()
        findViewById<Button>(R.id.btnSingle).setOnClickListener { singleShot() }

        spTimebase.onItemSelectedListener = object : SimpleSel() {
            override fun onSel(pos: Int) {
                writeTimebase(pos)
            }
        }
        spTimebase2.onItemSelectedListener = object : SimpleSel() {
            override fun onSel(pos: Int) {
                writeTimebase(pos)
            }
        }
        spVolt1.onItemSelectedListener = object : SimpleSel() {
            override fun onSel(pos: Int) {
                devInfo?.let { init.applyChannel(it, 0, cbCh1.isChecked, pos) }
            }
        }
        spVolt2.onItemSelectedListener = object : SimpleSel() {
            override fun onSel(pos: Int) {
                devInfo?.let { init.applyChannel(it, 1, cbCh2.isChecked, pos) }
            }
        }

        // якщо прилад уже підключений — підключитись одразу
        scope.findDevice()?.let { dev ->
            if (scope.hasPermission(dev)) connect() else scope.requestPermission(dev) { connect() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        scope.disconnect()
    }

    private fun connect() {
        val dev = scope.findDevice()
        if (dev == null) {
            status.text = usbBusStatus() + " — сканую 10с..."
            toast("Прилад не знайдено, сканую 10с — встав тепер OTG")
            // Фоновий рескан: прилад може стартувати кілька секунд після вставляння
            Thread {
                for (i in 1..20) {
                    Thread.sleep(500)
                    val d = scope.findDevice()
                    if (d != null) {
                        runOnUiThread {
                            if (scope.hasPermission(d)) connect() else scope.requestPermission(d) { connect() }
                        }
                        return@Thread
                    }
                    if (i % 4 == 0) runOnUiThread { status.text = usbBusStatus() + " — ще шукаю..." }
                }
                runOnUiThread { status.text = usbBusStatus() + " — не з'явився за 10с" }
            }.start()
            return
        }
        if (!scope.hasPermission(dev)) {
            scope.requestPermission(dev) { connect() }
            return
        }
        // Хендшейк у фоновому потоці з повторами: MCU стартує до ~5с після подачі живлення
        Thread {
            if (!scope.connect(dev)) {
                runOnUiThread { toast("Не вдалося відкрити USB-інтерфейс") }
                return@Thread
            }
            var mt: Int? = null
            for (i in 1..6) {
                mt = scope.queryMachineType()
                if (mt != null && mt > 0) break
                runOnUiThread { status.text = "Прилад стартує... спроба $i/6" }
                Thread.sleep(1000)
            }
            if (mt == null || mt <= 0) {
                scope.disconnect()
                runOnUiThread {
                    toast("MACHINE_TYPE: нема відповіді. Витягни OTG на 5с і встав знову")
                    status.text = usbBusStatus() + " — знову ПІДКЛЮЧИТИ"
                }
                return@Thread
            }
            runOnUiThread { status.text = "Підключено (тип $mt). Ініціалізація..." }
            val info = init.run()
            runOnUiThread {
                if (info == null) {
                    status.text = "Ініціалізація не вдалась (див. рядок вище)"
                } else {
                    devInfo = info
                    status.text = "VDS1022 v${info.version} №${info.serial} готово" +
                        (if (info.fpgaWasLoaded) " (FPGA завантажено)" else "")
                    applySettings()
                }
            }
        }.start()
    }

    @SuppressLint("SetTextI18n")
    private fun applySettings() {
        val info = devInfo ?: return
        val c1 = cbCh1.isChecked; val c2 = cbCh2.isChecked
        val v1 = spVolt1.selectedItemPosition; val v2 = spVolt2.selectedItemPosition
        val tb = spTimebase.selectedItemPosition
        usbExecutor.execute { init.configureAcquisition(info, c1, c2, v1, v2, tb) }
    }

    private fun writeTimebase(pos: Int) {
        if (devInfo == null) return
        val div = VdsProtocol.SEPERATE_FREQ.getOrElse(pos) { 1 }
        scope.writeCmd(VdsProtocol.ADD_TIMEBASE, 4, div)
        scope.writeCmd(VdsProtocol.ADD_SLOWMOVE, 1, if (pos >= 22) 1 else 0)
    }

    private fun toggleRun() {
        if (running) {
            running = false
            btnRun.text = getString(R.string.run)
            usbExecutor.execute { scope.writeCmd(VdsProtocol.ADD_RUNSTOP, 1, 1) }  // STOP=1
            return
        }
        if (!scope.isConnected) { toast("Спершу підключи прилад"); return }
        running = true
        btnRun.text = getString(R.string.stop)
        pollThread = Thread {
            applySettings()
            scope.writeCmd(VdsProtocol.ADD_RE_COLLECT, 1, 1)  // свіжий збір
            scope.writeCmd(VdsProtocol.ADD_RUNSTOP, 1, 0)     // RUN=0 (sendRun)
            var lastStatAt = 0L
            var phase = 0
            var writtenTb = -1
            while (running) {
                val ch1On = cbCh1.isChecked
                val ch2On = cbCh2.isChecked
                val tb1 = spTimebase.selectedItemPosition
                val tb2 = spTimebase2.selectedItemPosition
                // окремі розгортки: коли обидва канали увімкнені і TB різні,
                // acquisition чергується — CH1 знімається зі своєю розгорткою, CH2 зі своєю
                val alternating = ch1On && ch2On && tb1 != tb2
                val tbIdx = when {
                    alternating -> { phase = phase xor 1; if (phase == 0) tb1 else tb2 }
                    ch1On -> tb1
                    else -> tb2
                }
                if (tbIdx != writtenTb) {
                    writeTimebase(tbIdx)
                    writtenTb = tbIdx
                }
                val frames = scope.readFrame(ch1On, ch2On)
                if (frames != null) {
                    framesCount++
                    // швидкість вибірки: 100 МГц / дільник розгортки (ПК: 2.5MS/s на 100us)
                    val tpp = VdsProtocol.SEPERATE_FREQ.getOrElse(tbIdx) { 1 } / 1e8
                    var stat = "Кадрів: $framesCount T${scope.lastTrg}F${scope.lastFin}" +
                        " S=" + Measure.formatSampleRate(1 / tpp) +
                        (if (alternating) " TB:${VdsProtocol.TIMEBASE_LABELS[tb1]}/${VdsProtocol.TIMEBASE_LABELS[tb2]}" else "")
                    // обчислення (тригер-вікно, частота, Vpp) на робочому потоці — UI не блокуємо
                    var show1: ByteArray? = null
                    var show2: ByteArray? = null
                    for (f in frames) {
                        // при чергуванні показуємо лише канал, чия розгортка активна цього кадру
                        if (alternating && ((phase == 0 && f.chl != 0) || (phase == 1 && f.chl != 1))) continue
                        val shown = Measure.displayWindow(f.samples)
                        when (f.chl) { 0 -> show1 = shown; 1 -> show2 = shown }
                        val vbLabel = when (f.chl) {
                            0 -> VdsProtocol.VOLTBASE_LABELS[spVolt1.selectedItemPosition]
                            else -> VdsProtocol.VOLTBASE_LABELS[spVolt2.selectedItemPosition]
                        }
                        val vpp = Measure.vppVolts(f.samples, Measure.labelToVolts(vbLabel))
                        val fq = Measure.freqHz(f.samples, tpp)
                        var cmn = 127; var cmx = -128
                        for (b in f.samples) { val v = b.toInt(); if (v < cmn) cmn = v; if (v > cmx) cmx = v }
                        stat += "  CH${f.chl + 1} ${Measure.formatFreq(fq)} Vpp=" +
                            (if (vpp != null) "%.2fV".format(vpp) else "--") +
                            " [$vbLabel, коди ${cmn}..${cmx}]"
                    }
                    val finalStat = stat
                    runOnUiThread {
                        show1?.let { scopeView.ch1 = it }
                        show2?.let { scopeView.ch2 = it }
                        scopeView.invalidate()
                        // статус оновлюємо до 10 раз/с, сітку — кожен кадр
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastStatAt > 100) { lastStatAt = now; status.text = finalStat }
                    }
                    Thread.sleep(5)
                } else {
                    runOnUiThread { status.text = "Нема кадру [${scope.dbg}] T${scope.lastTrg} F${scope.lastFin}" }
                    Thread.sleep(20)
                }
            }
        }.also { it.start() }
    }

    private fun singleShot() {
        if (!scope.isConnected) { toast("Спершу підключи прилад"); return }
        Thread {
            scope.writeCmd(VdsProtocol.ADD_RE_COLLECT, 1, 1)
            var tries = 0
            while (tries++ < 50) {
                val frames = scope.readFrame(cbCh1.isChecked, cbCh2.isChecked)
                if (frames != null) {
                    runOnUiThread {
                        for (f in frames) {
                            val shown = Measure.displayWindow(f.samples)
                            when (f.chl) {
                                0 -> scopeView.ch1 = shown
                                1 -> scopeView.ch2 = shown
                            }
                        }
                        scopeView.invalidate()
                    }
                    return@Thread
                }
                Thread.sleep(100)
            }
            runOnUiThread { toast("Тригер не спрацював (таймаут)") }
        }.start()
    }

    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private open class SimpleSel : android.widget.AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        override fun onItemSelected(
            parent: android.widget.AdapterView<*>, view: android.view.View?, pos: Int, id: Long
        ) { onSel(pos) }
        open fun onSel(pos: Int) {}
    }
}
