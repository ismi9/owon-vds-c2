package com.ismi9.vdsscope

import android.annotation.SuppressLint
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
    private lateinit var spVolt1: Spinner
    private lateinit var spVolt2: Spinner
    private lateinit var btnRun: Button

    @Volatile private var running = false
    private var pollThread: Thread? = null
    private var framesCount = 0
    private var devInfo: DeviceInfo? = null
    private val usbExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private lateinit var init: DeviceInit

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
        spVolt1 = findViewById(R.id.spVolt1)
        spVolt2 = findViewById(R.id.spVolt2)
        btnRun = findViewById(R.id.btnRun)

        spTimebase.adapter = ArrayAdapter(
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
        findViewById<Button>(R.id.btnSingle).setOnClickListener { singleShot() }

        spTimebase.onItemSelectedListener = object : SimpleSel() {
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
            toast("Прилад OWON VDS не знайдено (перевір OTG)")
            return
        }
        if (!scope.hasPermission(dev)) {
            scope.requestPermission(dev) { connect() }
            return
        }
        if (!scope.connect(dev)) {
            toast("Не вдалося відкрити USB-інтерфейс")
            return
        }
        val mt = scope.queryMachineType()
        if (mt == null || mt <= 0) {
            scope.disconnect()
            toast("Прилад не відповів на MACHINE_TYPE")
            return
        }
        status.text = "Підключено (тип $mt). Ініціалізація..."
        Thread {
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
            while (running) {
                val frames = scope.readFrame(cbCh1.isChecked, cbCh2.isChecked)
                if (frames != null) {
                    framesCount++
                    var stat = "Кадрів: $framesCount T${scope.lastTrg}F${scope.lastFin}"
                    runOnUiThread {
                        for (f in frames) {
                            var mn = 127; var mx = -128
                            for (b in f.samples) { val v = b.toInt(); if (v < mn) mn = v; if (v > mx) mx = v }
                            stat += "  CH${f.chl + 1}[$mn..$mx]"
                            when (f.chl) {
                                0 -> scopeView.ch1 = f.samples
                                1 -> scopeView.ch2 = f.samples
                            }
                        }
                        scopeView.invalidate()
                        status.text = stat
                    }
                    Thread.sleep(50)
                } else {
                    runOnUiThread { status.text = "Нема кадру [${scope.dbg}] T${scope.lastTrg} F${scope.lastFin}" }
                    Thread.sleep(50)
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
                            when (f.chl) {
                                0 -> scopeView.ch1 = f.samples
                                1 -> scopeView.ch2 = f.samples
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
