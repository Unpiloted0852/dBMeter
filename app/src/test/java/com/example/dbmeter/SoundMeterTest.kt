package com.example.dbmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class SoundMeterTest {

    private val rate = 48000

    private fun sine(frequency: Double, rms: Double, seconds: Double) =
        ShortArray((rate * seconds).toInt()) { i ->
            (rms * sqrt(2.0) * sin(2 * PI * frequency * i / rate)).toInt().toShort()
        }

    private fun measure(samples: ShortArray, weighting: Weighting, response: Response, cal: Double): Reading {
        val meter = LevelMeter(rate, weighting, response).apply { calibrationDb = cal }
        return meter.process(samples, samples.size)
    }

    @Test
    fun aWeightingMatchesIec61672() {
        val filter = WeightingFilter(Weighting.A, rate)
        val table = mapOf(
            31.5 to -39.4, 63.0 to -26.2, 125.0 to -16.1, 250.0 to -8.6, 500.0 to -3.2,
            1000.0 to 0.0, 2000.0 to 1.2, 4000.0 to 1.0
        )
        for ((f, expected) in table) assertEquals("A at $f Hz", expected, filter.responseDb(f), 0.2)
        assertEquals(-1.1, filter.responseDb(8000.0), 1.0)
    }

    @Test
    fun cWeightingMatchesIec61672() {
        val filter = WeightingFilter(Weighting.C, rate)
        val table = mapOf(31.5 to -3.0, 63.0 to -0.8, 125.0 to -0.2, 1000.0 to 0.0, 4000.0 to -0.8)
        for ((f, expected) in table) assertEquals("C at $f Hz", expected, filter.responseDb(f), 0.2)
    }

    @Test
    fun androidReferenceTonesReadTheirSpl() {
        // The two sensitivities Android's compatibility rules prescribe.
        val unprocessed = measure(sine(1000.0, 520.0, 3.0), Weighting.A, Response.FAST, AudioCapture.UNPROCESSED_FULL_SCALE_DB)
        assertEquals(94.0, unprocessed.level, 0.1)
        assertEquals(94.0, unprocessed.leq!!, 0.1)
        val voice = measure(sine(1000.0, 2500.0, 3.0), Weighting.C, Response.SLOW, AudioCapture.VOICE_RECOGNITION_FULL_SCALE_DB)
        assertEquals(90.0, voice.level, 0.1)
        assertEquals(90.0, voice.max!!, 0.1)
        assertEquals(90.0, voice.min!!, 0.1)
    }

    @Test
    fun readingFollowsTheFilteredSignalNotJustTheFormula() {
        // 100 Hz is 19.1 dB down under A weighting and untouched under Z.
        val tone = sine(100.0, 2500.0, 3.0)
        assertEquals(90.0 - 19.1, measure(tone, Weighting.A, Response.FAST, 112.35).level, 0.2)
        assertEquals(90.0, measure(tone, Weighting.Z, Response.FAST, 112.35).level, 0.1)
    }

    @Test
    fun sameLevelForToneAndNoise() {
        val random = Random(1)
        // Gaussian-ish noise with RMS 2500: sum of 12 uniforms.
        val noise = ShortArray(rate * 10) {
            var g = -6.0
            repeat(12) { g += random.nextDouble() }
            (g * 2500).toInt().toShort()
        }
        assertEquals(90.0, measure(noise, Weighting.Z, Response.SLOW, 112.35).leq!!, 0.2)
    }

    @Test
    fun leqIsAnEnergyAverageAndMaxCatchesABurst() {
        // 9 s at 60 dB and 1 s at 100 dB: Leq = 10*log10((9*1e6 + 1e10) / 10) = 90.0
        val quiet = sine(1000.0, 2500.0 / 31.623, 9.0)
        val loud = sine(1000.0, 2500.0 * 3.1623, 1.0)
        val meter = LevelMeter(rate, Weighting.A, Response.FAST).apply { calibrationDb = 112.35 }
        // Skip the start-up samples so the statistics cover exactly the 10 s below.
        meter.process(quiet, rate)
        meter.resetStats()
        meter.process(quiet, quiet.size)
        val reading = meter.process(loud, loud.size)
        assertEquals(90.0, reading.leq!!, 0.1)
        assertEquals(100.0, reading.max!!, 0.1)
        assertEquals(60.0, reading.min!!, 0.2)
    }

    @Test
    fun fastResponseDecaysAtItsTimeConstant() {
        val meter = LevelMeter(rate, Weighting.Z, Response.FAST).apply { calibrationDb = 112.35 }
        val tone = sine(1000.0, 2500.0, 2.0)
        meter.process(tone, tone.size)
        // One time constant (125 ms) of silence drops the level by 10*log10(e) = 4.34 dB.
        val silence = ShortArray(rate / 8)
        assertEquals(90.0 - 4.34, meter.process(silence, silence.size).level, 0.1)
    }

    @Test
    fun flagsOverload() {
        assertTrue(measure(sine(1000.0, 2500.0, 1.0).also { it[10] = Short.MAX_VALUE }, Weighting.A, Response.FAST, 112.35).overload)
        assertTrue(!measure(sine(1000.0, 2500.0, 1.0), Weighting.A, Response.FAST, 112.35).overload)
    }
}
