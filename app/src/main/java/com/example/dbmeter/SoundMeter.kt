package com.example.dbmeter

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

enum class Weighting(val label: String) { A("dBA"), C("dBC"), Z("dBZ") }

enum class Response(val label: String, val tauSeconds: Double) {
    FAST("Fast", 0.125),
    SLOW("Slow", 1.0)
}

/**
 * IEC 61672 frequency weighting as a cascade of first-order sections (the analog
 * prototype's real poles, bilinear-transformed), normalised to 0 dB at 1 kHz.
 * Z is flat apart from a 10 Hz high-pass that removes the microphone's DC offset.
 */
class WeightingFilter(weighting: Weighting, private val sampleRate: Int) {

    // Section i: y[n] = x[n] + b[i]*x[n-1] - a[i]*y[n-1]; b = -1 is a high-pass, +1 a low-pass.
    private val a: DoubleArray
    private val b: DoubleArray
    private val x1: DoubleArray
    private val y1: DoubleArray
    private val gain: Double

    init {
        val f1 = 20.598997
        val f2 = 107.65265
        val f3 = 737.86223
        val f4 = 12194.217
        val highPass: List<Double>
        val lowPass: List<Double>
        when (weighting) {
            Weighting.A -> { highPass = listOf(f1, f1, f2, f3); lowPass = listOf(f4, f4) }
            Weighting.C -> { highPass = listOf(f1, f1); lowPass = listOf(f4, f4) }
            Weighting.Z -> { highPass = listOf(10.0); lowPass = emptyList() }
        }
        val poles = highPass + lowPass
        a = DoubleArray(poles.size) { i ->
            val w = 2 * PI * poles[i]
            (w - 2.0 * sampleRate) / (w + 2.0 * sampleRate)
        }
        b = DoubleArray(poles.size) { i -> if (i < highPass.size) -1.0 else 1.0 }
        x1 = DoubleArray(poles.size)
        y1 = DoubleArray(poles.size)
        gain = 1.0 / magnitude(1000.0)
    }

    fun process(sample: Double): Double {
        var v = sample
        for (i in a.indices) {
            val y = v + b[i] * x1[i] - a[i] * y1[i]
            x1[i] = v
            y1[i] = y
            v = y
        }
        return v * gain
    }

    /** Gain of the filter at [frequency], in dB. */
    fun responseDb(frequency: Double): Double = 20 * log10(magnitude(frequency) * gain)

    private fun magnitude(frequency: Double): Double {
        val w = 2 * PI * frequency / sampleRate
        var m = 1.0
        for (i in a.indices) {
            val num = sqrt((1 + b[i] * cos(w)).let { it * it } + (b[i] * sin(w)).let { it * it })
            val den = sqrt((1 + a[i] * cos(w)).let { it * it } + (a[i] * sin(w)).let { it * it })
            m *= num / den
        }
        return m
    }
}

/** One set of readings, in dB SPL. The statistics are null until the meter has settled. */
data class Reading(
    val level: Double,
    val leq: Double?,
    val max: Double?,
    val min: Double?,
    val overload: Boolean
)

/**
 * Sound level meter maths on 16-bit PCM: frequency weighting, exponential Fast/Slow
 * time weighting of the squared signal, and Leq/Lmax/Lmin since the last reset.
 *
 * [calibrationDb] is the sound pressure level of a full-scale sine's RMS + 3 dB, i.e.
 * SPL = 20*log10(rms / 32768) + calibrationDb.
 */
class LevelMeter(sampleRate: Int, weighting: Weighting, response: Response) {

    @Volatile var calibrationDb = 0.0

    private val filter = WeightingFilter(weighting, sampleRate)
    private val alpha = 1 - exp(-1.0 / (sampleRate * response.tauSeconds))

    // The first samples only run the filter (microphone and filter start-up), the next
    // ones seed the time-weighted level so it does not have to climb up from silence.
    private var warmup = (sampleRate * 0.3).toInt()
    private val seedLength = (sampleRate * 0.125).toInt()
    private var seedCount = 0
    private var seedSum = 0.0

    private var meanSquare = 0.0
    private var sumSquares = 0.0
    private var count = 0L
    private var maxMeanSquare = 0.0
    private var minMeanSquare = Double.MAX_VALUE
    private var overload = false

    fun resetStats() {
        sumSquares = 0.0
        count = 0
        maxMeanSquare = 0.0
        minMeanSquare = Double.MAX_VALUE
    }

    /** Feeds [length] samples and returns the readings as of the last one. */
    fun process(samples: ShortArray, length: Int): Reading {
        overload = false
        for (i in 0 until length) {
            val s = samples[i].toInt()
            if (abs(s) >= CLIP_LEVEL) overload = true
            val y = filter.process(s / 32768.0)
            val sq = y * y
            if (warmup > 0) {
                warmup--
            } else if (seedCount < seedLength) {
                seedSum += sq
                if (++seedCount == seedLength) meanSquare = seedSum / seedLength
            } else {
                meanSquare += (sq - meanSquare) * alpha
                sumSquares += sq
                count++
                if (meanSquare > maxMeanSquare) maxMeanSquare = meanSquare
                if (meanSquare < minMeanSquare) minMeanSquare = meanSquare
            }
        }
        val cal = calibrationDb
        val settled = count > 0
        return Reading(
            level = toDb(meanSquare) + cal,
            leq = if (settled) toDb(sumSquares / count) + cal else null,
            max = if (settled) toDb(maxMeanSquare) + cal else null,
            min = if (settled) toDb(minMeanSquare) + cal else null,
            overload = overload
        )
    }

    private fun toDb(meanSquare: Double) = 10 * log10(meanSquare.coerceAtLeast(1e-12))

    companion object {
        private const val CLIP_LEVEL = 32700
    }
}
