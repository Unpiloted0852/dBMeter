package com.example.dbmeter

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MicrophoneInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.Locale
import kotlin.math.max

/**
 * Reads the microphone on its own thread and delivers a [Reading] to [onReading] on the
 * main thread about ten times a second.
 *
 * It records raw 16-bit PCM from the UNPROCESSED source where the phone supports it,
 * otherwise from VOICE_RECOGNITION; neither applies automatic gain or noise suppression.
 * Android's compatibility rules fix the sensitivity of both (94 dB SPL at 1 kHz gives an
 * RMS of 520 for UNPROCESSED, 90 dB SPL gives 2500 for VOICE_RECOGNITION), which is where
 * the default calibration comes from, unless the phone reports its own microphone
 * sensitivity. [trimDb] is the user's correction on top of it.
 */
class AudioCapture(
    context: Context,
    private val weighting: Weighting,
    private val response: Response,
    trimDb: Double,
    private val onReading: (Reading) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val unprocessed = (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
        .getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

    @Volatile private var running = false
    @Volatile private var resetRequested = false
    @Volatile private var meter: LevelMeter? = null
    private var baseCalibrationDb = 0.0

    /** What is being measured with, for the calibration dialog. */
    var description = ""
        private set

    /** The level a full-scale signal reads, before the trim. */
    val fullScaleDb get() = baseCalibrationDb
    private var thread: Thread? = null

    var trimDb = trimDb
        set(value) {
            field = value
            meter?.calibrationDb = baseCalibrationDb + value
        }

    /** Returns false if the microphone could not be opened. */
    @SuppressLint("MissingPermission") // The activity only calls this once RECORD_AUDIO is granted.
    fun start(): Boolean {
        if (running) return true
        val sources = if (unprocessed) {
            listOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.VOICE_RECOGNITION)
        } else {
            listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        }
        for (source in sources) {
            for (rate in intArrayOf(48000, 44100)) {
                val minSize = AudioRecord.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                if (minSize <= 0) continue
                val record = try {
                    AudioRecord(
                        source, rate, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, max(minSize, rate / 5 * 2)
                    )
                } catch (e: IllegalArgumentException) {
                    continue
                }
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    continue
                }
                try {
                    record.startRecording()
                } catch (e: IllegalStateException) {
                    record.release()
                    continue
                }
                val isUnprocessed = source == MediaRecorder.AudioSource.UNPROCESSED
                val reported = if (isUnprocessed) reportedSensitivity(record) else null
                baseCalibrationDb = when {
                    reported != null -> 94.0 - reported
                    isUnprocessed -> UNPROCESSED_FULL_SCALE_DB
                    else -> VOICE_RECOGNITION_FULL_SCALE_DB
                }
                description = String.format(
                    Locale.US, "%s source, %d kHz, full scale %.1f dB (%s)",
                    if (isUnprocessed) "Unprocessed" else "Voice recognition", rate / 1000,
                    baseCalibrationDb, if (reported != null) "reported by phone" else "Android reference"
                )
                val m = LevelMeter(rate, weighting, response)
                m.calibrationDb = baseCalibrationDb + trimDb
                meter = m
                running = true
                thread = Thread({ readLoop(record, m, rate / 10) }, "dBMeter-audio").apply { start() }
                return true
            }
        }
        return false
    }

    /** The phone's own figure for its microphone, in dBFS at 94 dB SPL, if it gives a usable one. */
    private fun reportedSensitivity(record: AudioRecord): Double? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val microphones = try {
            record.activeMicrophones
        } catch (e: Exception) {
            return null
        }
        return microphones.map { it.sensitivity }
            .firstOrNull { it != MicrophoneInfo.SENSITIVITY_UNKNOWN && it in -60f..-10f }
            ?.toDouble()
    }

    fun resetStats() {
        resetRequested = true
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
        meter = null
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun readLoop(record: AudioRecord, meter: LevelMeter, blockSize: Int) {
        val buffer = ShortArray(blockSize)
        try {
            while (running) {
                var filled = 0
                while (filled < blockSize && running) {
                    val n = record.read(buffer, filled, blockSize - filled)
                    if (n <= 0) return
                    filled += n
                }
                if (resetRequested) {
                    resetRequested = false
                    meter.resetStats()
                }
                val reading = meter.process(buffer, filled)
                mainHandler.post { if (running) onReading(reading) }
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    companion object {
        // SPL = 20*log10(rms / 32768) + this.
        const val UNPROCESSED_FULL_SCALE_DB = 130.0   // 94 dB SPL -> RMS 520 (-36 dBFS)
        const val VOICE_RECOGNITION_FULL_SCALE_DB = 112.35 // 90 dB SPL -> RMS 2500
    }
}
