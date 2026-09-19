package dev.breathwork.pacer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Amplitude-ramped breathing pacer.
 *
 * Phases are played as single VibrationEffect waveforms, so the ramp happens inside the
 * vibrator HAL rather than being re-triggered from a timer - no jitter, and it keeps
 * ramping if the UI thread stutters.
 */
class MainActivity : Activity() {

    companion object {
        /** Open intent extra: slot id to pre-select (from a reminder tap). */
        const val EXTRA_SLOT = "dev.breathwork.pacer.SLOT"
        private const val NOTIF_REQ = 5101
    }

    private lateinit var vibrator: Vibrator
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var startStop: Button
    private lateinit var halfButton: Button
    private val slotButtons = ArrayList<Button>()
    private lateinit var reminderMaster: Button
    private val reminderButtons = ArrayList<Button>()

    private var selected = 2                     // 11:00 resonance - the anchor
    private var half = false
    private var scale = 0.55                     // strength, persisted
    private var pulseHz = 2.5                    // pulse rate, persisted
    private lateinit var strengthLabel: TextView
    private lateinit var rateButton: Button
    @Volatile private var running = false
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vibrator = resolveVibrator()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Deep link from a reminder tap: pre-select that slot.
        intent?.getStringExtra(EXTRA_SLOT)?.let { id ->
            Patterns.SLOTS.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { selected = it }
        }
        setContentView(buildUi())
        loadPrefs()
        refreshCapability()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_SLOT)?.let { id ->
            Patterns.SLOTS.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let {
                select(it)
            }
        }
    }

    private fun resolveVibrator(): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(22))
        }

        root.addView(TextView(this).apply {
            text = "Breath Pacer"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        })

        detail = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(6), 0, dp(14))
        }
        root.addView(detail)

        Patterns.SLOTS.forEachIndexed { i, slot ->
            val b = Button(this).apply {
                text = "${slot.time}  ${slot.name}"
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { select(i) }
            }
            slotButtons.add(b)
            root.addView(b, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val tune = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, 0)
        }
        val softer = Button(this).apply { text = "−"; isAllCaps = false; setOnClickListener { bumpScale(-0.05) } }
        strengthLabel = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        val louder = Button(this).apply { text = "+"; isAllCaps = false; setOnClickListener { bumpScale(0.05) } }
        listOf(softer, strengthLabel, louder).forEach {
            tune.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(tune)

        rateButton = Button(this).apply {
            isAllCaps = false
            setOnClickListener { cycleRate() }
        }
        root.addView(rateButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(18), 0, dp(8))
        }
        startStop = Button(this).apply {
            text = "Start"
            isAllCaps = false
            setOnClickListener { if (running) stopSession() else startSession() }
        }
        halfButton = Button(this).apply {
            text = "Half"
            isAllCaps = false
            alpha = 0.5f
            setOnClickListener {
                if (running) return@setOnClickListener
                half = !half
                alpha = if (half) 1f else 0.5f
                select(selected)
            }
        }
        val test = Button(this).apply {
            text = "Test ramp"
            isAllCaps = false
            setOnClickListener { testRamp() }
        }
        listOf(startStop, halfButton, test).forEach {
            row.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(row)

        status = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(0, dp(18), 0, 0)
            text = "Pick a slot, then Start."
        }
        root.addView(status)

        root.addView(TextView(this).apply {
            text = "Reminders"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, dp(22), 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = "A daily notification per slot. Tap one to open that session."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 0, 0, dp(6))
        })

        reminderMaster = Button(this).apply {
            isAllCaps = false
            setOnClickListener { toggleMaster() }
        }
        root.addView(reminderMaster, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        reminderButtons.clear()
        Patterns.SLOTS.forEachIndexed { i, slot ->
            val b = Button(this).apply {
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { toggleSlot(i) }
            }
            reminderButtons.add(b)
            root.addView(b, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        refreshReminders()

        select(selected)
        return ScrollView(this).apply { addView(root) }
    }

    private fun select(i: Int) {
        selected = i
        slotButtons.forEachIndexed { k, b -> b.alpha = if (k == i) 1f else 0.5f }
        if (!running) {
            val p = Patterns.phases(Patterns.SLOTS[i], half)
            status.text = "${Patterns.SLOTS[i].time} ${Patterns.SLOTS[i].name}\n" +
                "${p.size} phases · ${Patterns.totalMillis(p) / 60000} min" + if (half) " (half)" else "" +
                "\npulsed at $pulseHz Hz · strength ${Math.round(scale * 100)}%"
        }
    }

    private fun prefs() = getSharedPreferences("breathpacer", MODE_PRIVATE)

    // ---------------- Reminders ----------------

    private fun toggleMaster() {
        val on = !Reminders.isMasterOn(this)
        if (on && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), NOTIF_REQ)
            return
        }
        Reminders.setMaster(this, on)
        refreshReminders()
    }

    private fun toggleSlot(i: Int) {
        val slot = Patterns.SLOTS[i]
        if (!Reminders.isMasterOn(this)) {
            // One tap enables everything when the master is off.
            toggleMaster()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), NOTIF_REQ)
            return
        }
        Reminders.setSlot(this, slot.id, !Reminders.isOn(this, slot.id))
        refreshReminders()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIF_REQ &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            // The tap that triggered the prompt meant "on".
            if (!Reminders.isMasterOn(this)) Reminders.setMaster(this, true)
            refreshReminders()
        }
    }

    private fun refreshReminders() {
        val master = Reminders.isMasterOn(this)
        reminderMaster.text = if (master) "🔔 Reminders ON — tap to pause all" else "🔕 Reminders OFF — tap to enable all"
        Patterns.SLOTS.forEachIndexed { i, slot ->
            val on = Reminders.isOn(this, slot.id)
            reminderButtons[i].text = (if (on && master) "🔔 " else "◻ ") + "${slot.time}  ${slot.name}"
            reminderButtons[i].alpha = if (on && master) 1f else 0.55f
        }
    }

    private fun loadPrefs() {
        scale = prefs().getFloat("scale", 0.55f).toDouble()
        pulseHz = prefs().getFloat("pulseHz", 2.5f).toDouble()
        refreshControls()
    }

    private fun bumpScale(d: Double) {
        if (running) return
        scale = (scale + d).coerceIn(0.15, 1.0)
        prefs().edit().putFloat("scale", scale.toFloat()).apply()
        refreshControls()
    }

    private fun cycleRate() {
        if (running) return
        val rates = doubleArrayOf(2.0, 2.5, 3.0)
        val i = rates.indexOfFirst { Math.abs(it - pulseHz) < 0.01 }
        pulseHz = rates[(i + 1) % rates.size]
        prefs().edit().putFloat("pulseHz", pulseHz.toFloat()).apply()
        refreshControls()
    }

    private fun refreshControls() {
        strengthLabel.text = "Strength ${Math.round(scale * 100)}%"
        rateButton.text = "Pulse $pulseHz Hz — tap to change"
        if (!running) select(selected)
    }

    private fun opts() = Patterns.Options(pulseHz = pulseHz, scale = scale)

    private fun refreshCapability() {
        val amp = vibrator.hasAmplitudeControl()
        detail.text = buildString {
            append(if (amp) "✅ amplitude control supported" else "⚠️ NO amplitude control - every pulse will play at full strength")
            append(" · ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
            append(" · Android ").append(Build.VERSION.RELEASE)
        }
    }

    private fun vibrate(w: Patterns.Waveform) {
        if (!vibrator.hasVibrator()) return
        try {
            vibrator.vibrate(VibrationEffect.createWaveform(w.timings, w.amplitudes, -1))
        } catch (e: Exception) {
            runOnUiThread { detail.text = "vibration rejected: ${e.message}" }
        }
    }

    private fun startSession() {
        if (running) return
        val slot = Patterns.SLOTS[selected]
        val phases = Patterns.phases(slot, half)
        val cum = Patterns.phaseMillis(phases)
        val totalMs = Patterns.totalMillis(phases)
        running = true
        startStop.text = "Stop"
        acquireWakelock()
        slotButtons.forEach { it.alpha = 0.4f }

        worker = Thread {
            val t0 = SystemClock.elapsedRealtime()
            var pulses = 0L
            try {
                for ((i, ph) in phases.withIndex()) {
                    val phaseStart = t0 + cum[i]
                    val phaseMs = Math.round(ph.seconds * 1000.0)
                    if (ph.kind == Patterns.Kind.FREE) {           // gentle pulse per breath
                        var next = 0L
                        while (running && SystemClock.elapsedRealtime() < phaseStart + phaseMs) {
                            if (SystemClock.elapsedRealtime() >= phaseStart + next) {
                                vibrate(Patterns.waveform(Patterns.Kind.FREE, 0.2, opts()))
                                pulses++
                                next += 11_000L
                            }
                            Thread.sleep(40)
                        }
                    } else {
                        while (running && SystemClock.elapsedRealtime() < phaseStart) Thread.sleep(4)
                        if (!running) break
                        vibrate(Patterns.waveform(ph.kind, ph.seconds, opts()))
                    }
                    val elapsedSec = (SystemClock.elapsedRealtime() - phaseStart) / 1000
                    val left = (phaseMs / 1000 - elapsedSec).coerceAtLeast(0)
                    val toGo = ((totalMs - cum[i]) / 1000 - elapsedSec).coerceAtLeast(0)
                    runOnUiThread {
                        val cyc = if (ph.cycles > 0) " · cycle ${ph.cycle}/${ph.cycles}" else ""
                        status.text = "${ph.kind.name.lowercase()}$cyc\n${left}s left · ${toGo}s to go"
                    }
                }
                runOnUiThread { if (running) status.text = "Session complete — ${totalMs / 60000} min." }
            } catch (e: Exception) {
                runOnUiThread { status.text = "stopped: ${e.message}" }
            } finally {
                releaseWakelock()
                runOnUiThread {
                    running = false
                    startStop.text = "Start"
                    slotButtons.forEachIndexed { k, b -> b.alpha = if (k == selected) 1f else 0.5f }
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun stopSession() {
        running = false
        worker?.interrupt()
        worker = null
        try { vibrator.cancel() } catch (_: Exception) {}
        releaseWakelock()
        startStop.text = "Start"
        status.text = "Stopped."
        slotButtons.forEachIndexed { k, b -> b.alpha = if (k == selected) 1f else 0.5f }
    }

    /** Hear the shape: a 3 s inhale swell straight into a 4 s exhale fade. */
    private fun testRamp() {
        if (running) return
        val ms = 3_000L
        vibrate(Patterns.waveform(Patterns.Kind.INHALE, 3.0, opts()))
        status.text = "Test ramp: inhale swell 3 s, then exhale fade 4 s…"
        Thread {
            Thread.sleep(ms + 150)
            vibrate(Patterns.waveform(Patterns.Kind.EXHALE, 4.0, opts()))
            Thread.sleep(4_150)
            runOnUiThread { status.text = "That was the shape: swell in, fade out." }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun acquireWakelock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "breathpacer:session").apply {
                setReferenceCounted(false)
                acquire(60 * 60 * 1000L)
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakelock() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    override fun onDestroy() {
        stopSession()
        super.onDestroy()
    }
}
