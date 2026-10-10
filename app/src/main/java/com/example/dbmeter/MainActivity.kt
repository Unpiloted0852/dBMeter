package com.example.dbmeter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // UI Components
    private lateinit var tvUpdate: TextView
    private lateinit var tvDecibel: TextView
    private lateinit var tvUnit: TextView
    private lateinit var tvWarning: TextView
    private lateinit var tvExample: TextView
    private lateinit var viewSlider: View
    private lateinit var viewBarBackground: View
    private lateinit var tvStats: TextView
    private lateinit var btnWeighting: Button
    private lateinit var btnResponse: Button
    private lateinit var btnCalibrate: Button
    private lateinit var btnReset: Button
    private lateinit var graphView: GraphView

    // Audio Logic
    private var capture: AudioCapture? = null
    private val updater by lazy { AppUpdater(this) }
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    // Settings
    private var weighting = Weighting.A
    private var response = Response.FAST
    private var trimDb = 0.0
    private var preferUnprocessed = true

    private val historyData = mutableListOf<Pair<Long, Double>>() // timestamp, dB value
    private var lastLevel = 0.0
    private var lastRawLevel = 0.0
    private var tvCalibration: TextView? = null
    private var tvDiagnostics: TextView? = null

    // Permissions
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                startRecording()
            } else {
                tvExample.text = "Microphone Permission Required"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Same on every Android version: draw behind transparent system bars (light icons on
        // the dark background) and pad the content clear of them below.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // Keep screen on while app is open
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Link code to XML
        tvUpdate = findViewById(R.id.tvUpdate)
        tvDecibel = findViewById(R.id.tvDecibel)
        tvUnit = findViewById(R.id.tvUnit)
        tvWarning = findViewById(R.id.tvWarning)
        tvExample = findViewById(R.id.tvExample)
        viewSlider = findViewById(R.id.viewSlider)
        viewBarBackground = findViewById(R.id.viewBarBackground)
        tvStats = findViewById(R.id.tvStats)
        btnWeighting = findViewById(R.id.btnWeighting)
        btnResponse = findViewById(R.id.btnResponse)
        btnCalibrate = findViewById(R.id.btnCalibrate)
        btnReset = findViewById(R.id.btnReset)
        graphView = findViewById(R.id.graphView)

        weighting = Weighting.entries.firstOrNull { it.name == prefs.getString("weighting", null) } ?: Weighting.A
        response = Response.entries.firstOrNull { it.name == prefs.getString("response", null) } ?: Response.FAST
        trimDb = prefs.getFloat("trimDb", 0f).toDouble()
        preferUnprocessed = prefs.getBoolean("preferUnprocessed", true)
        updateSettingLabels()

        btnWeighting.setOnClickListener {
            weighting = Weighting.entries[(weighting.ordinal + 1) % Weighting.entries.size]
            applySettings()
        }
        btnResponse.setOnClickListener {
            response = Response.entries[(response.ordinal + 1) % Response.entries.size]
            applySettings()
        }
        btnCalibrate.setOnClickListener { showCalibrationDialog() }
        btnReset.setOnClickListener { resetStats() }

        // Check Permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        checkForUpdate()
    }

    /** Once per launch: if GitHub has a newer release, offer it as a tappable pill at the top. */
    private fun checkForUpdate() {
        lifecycleScope.launch {
            val release = updater.checkForUpdate() ?: return@launch
            val offer = "Update available: v${release.versionName}. Tap to install."
            var busy = false
            tvUpdate.text = offer
            tvUpdate.visibility = View.VISIBLE
            tvUpdate.setOnClickListener {
                if (busy) return@setOnClickListener
                busy = true
                lifecycleScope.launch {
                    val error = updater.downloadAndInstall(release) { pct ->
                        tvUpdate.text = if (pct < 100) "Downloading update… $pct%" else "Installing update…"
                    }
                    // Only reached if the update did not replace the running app.
                    busy = false
                    tvUpdate.text = offer
                    if (error != null && error != "cancelled") {
                        Toast.makeText(this@MainActivity, "Update failed: $error", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun startRecording() {
        if (capture != null) return
        val c = AudioCapture(this, weighting, response, preferUnprocessed, trimDb, ::onReading)
        if (c.start()) {
            capture = c
        } else {
            tvExample.text = "Microphone initialization failed. Please restart app."
        }
    }

    private fun stopRecording() {
        capture?.stop()
        capture = null
    }

    /** A change of weighting or response starts a fresh measurement: the old statistics no longer apply. */
    private fun applySettings() {
        prefs.edit()
            .putString("weighting", weighting.name)
            .putString("response", response.name)
            .putBoolean("preferUnprocessed", preferUnprocessed)
            .apply()
        updateSettingLabels()
        val wasRecording = capture != null
        stopRecording()
        historyData.clear()
        graphView.updateData(historyData)
        tvStats.text = formatStats(null)
        if (wasRecording) startRecording()
    }

    private fun updateSettingLabels() {
        tvUnit.text = weighting.label
        btnWeighting.text = weighting.label
        btnResponse.text = response.label
    }

    private fun onReading(reading: Reading) {
        // Shown from 0 up; the top is wherever the microphone overloads.
        val db = reading.level.coerceAtLeast(0.0)
        lastLevel = db
        lastRawLevel = reading.level

        if (reading.leq != null) {
            val currentTime = System.currentTimeMillis()
            historyData.add(Pair(currentTime, db))

            // Keep only last 20 seconds of data
            val cutoffTime = currentTime - 20000
            historyData.removeAll { it.first < cutoffTime }
        }

        updateUI(db, reading.overload)
        tvStats.text = formatStats(reading)
        graphView.updateData(historyData)
        tvCalibration?.text = calibrationText()
        tvDiagnostics?.text = diagnosticsText()
    }

    private fun updateUI(db: Double, overload: Boolean) {
        // 1. Update Text
        tvDecibel.text = String.format(Locale.US, "%.1f", db)

        // 2. Determine Color and Status based on loudness
        val color: Int
        var exampleText = ""
        var showWarning = false

        when {
            db < 40 -> {
                color = Color.parseColor("#4CAF50") // Green
                exampleText = "Whisper / Library"
            }
            db < 60 -> {
                color = Color.parseColor("#8BC34A") // Light Green
                exampleText = "Conversation"
            }
            db < 75 -> {
                color = Color.parseColor("#FFEB3B") // Yellow
                exampleText = "Traffic / Vacuum"
            }
            db < 85 -> {
                color = Color.parseColor("#FF9800") // Orange
                exampleText = "Loud Music"
            }
            else -> {
                color = Color.parseColor("#F44336") // Red
                exampleText = "Chainsaw / Siren"
                showWarning = true
            }
        }

        tvDecibel.setTextColor(color)
        tvExample.text = exampleText

        // Overload beats the OSHA warning: a clipped microphone reads lower than the real level.
        if (overload) {
            tvWarning.visibility = View.VISIBLE
            tvWarning.text = "⚠️ MIC OVERLOAD: TOO LOUD TO MEASURE"
        } else if (showWarning) {
            tvWarning.visibility = View.VISIBLE
            tvWarning.text = "⚠️ OSHA WARNING: LIMIT EXPOSURE"
        } else {
            tvWarning.visibility = View.INVISIBLE
        }

        // 3. Move the Slider
        // Map dB (0 to 110) to the height of the bar
        val barHeight = viewBarBackground.height
        val percentage = (db / 110.0).coerceIn(0.0, 1.0)

        // Move slider up (translationY is negative to go up)
        val pixelMove = (barHeight - viewSlider.height) * percentage
        viewSlider.translationY = -pixelMove.toFloat()
    }

    private fun formatStats(reading: Reading?): String {
        fun f(v: Double?) = if (v == null) "--" else String.format(Locale.US, "%.1f", v.coerceAtLeast(0.0))
        return "Max: ${f(reading?.max)}  |  Min: ${f(reading?.min)}  |  Leq: ${f(reading?.leq)}"
    }

    private fun resetStats() {
        capture?.resetStats()
        historyData.clear()
        tvStats.text = formatStats(null)
        graphView.updateData(historyData)
    }

    private fun calibrationText() = String.format(
        Locale.US, "Reading: %.1f %s\nTrim: %+.1f dB", lastLevel, weighting.label, trimDb
    )

    /** Small print for the calibration dialog: enough to tell what a wrong reading comes from. */
    private fun diagnosticsText(): String {
        val c = capture ?: return ""
        return String.format(Locale.US, "%s\nRaw level %.1f dBFS", c.description, lastRawLevel - c.fullScaleDb - trimDb)
    }

    /**
     * Phone microphones differ by a few dB from the Android reference sensitivity. The trim
     * is added to every reading, so matching one steady sound against a real meter fixes it.
     */
    private fun showCalibrationDialog() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val label = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            text = calibrationText()
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for (step in doubleArrayOf(-1.0, -0.1, 0.1, 1.0)) {
            buttons.addView(Button(this).apply {
                text = String.format(Locale.US, "%+.1f", step)
                setOnClickListener { setTrim(trimDb + step) }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val diagnostics = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, 0)
            text = diagnosticsText()
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(label)
            addView(buttons)
            addView(diagnostics)
            // The two sources differ in gain: one may suit a given phone better than the other.
            addView(Button(this@MainActivity).apply {
                text = "Switch microphone source"
                isAllCaps = false
                setOnClickListener {
                    preferUnprocessed = !preferUnprocessed
                    applySettings()
                }
            })
        }
        tvCalibration = label
        tvDiagnostics = diagnostics
        AlertDialog.Builder(this)
            .setTitle("Calibration")
            .setMessage("Hold the phone next to a reference sound level meter in steady noise and adjust until both show the same level.")
            .setView(content)
            .setPositiveButton("Done", null)
            .setNeutralButton("Reset to 0") { _, _ -> setTrim(0.0) }
            .setOnDismissListener {
                tvCalibration = null
                tvDiagnostics = null
            }
            .show()
    }

    private fun setTrim(value: Double) {
        trimDb = (Math.round(value.coerceIn(-30.0, 30.0) * 10) / 10.0)
        prefs.edit().putFloat("trimDb", trimDb.toFloat()).apply()
        capture?.trimDb = trimDb
        tvCalibration?.text = calibrationText()
    }

    override fun onStart() {
        super.onStart()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        }
    }

    override fun onStop() {
        super.onStop()
        stopRecording()
    }
}
