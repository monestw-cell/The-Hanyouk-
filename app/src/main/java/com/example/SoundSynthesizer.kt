package com.example

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.sin

object SoundSynthesizer {

    private val scope = CoroutineScope(Dispatchers.Default)
    private const val SAMPLE_RATE = 22050

    fun playNightFall() {
        scope.launch {
            // Sequence of descending dark pitch sweeps
            playToneSweep(440f, 150f, 600)
            kotlinx.coroutines.delay(100)
            playToneSweep(330f, 110f, 800)
        }
    }

    fun playDayDawn() {
        scope.launch {
            // High-pitched bright chimes
            playToneSweep(250f, 650f, 300)
            kotlinx.coroutines.delay(50)
            playToneSweep(450f, 950f, 400)
        }
    }

    fun playDeath() {
        scope.launch {
            // Somber minor chords
            playSine(293.66f, 400)  // D4
            kotlinx.coroutines.delay(50)
            playSine(349.23f, 400)  // F4
            kotlinx.coroutines.delay(50)
            playSine(440.00f, 700)  // A4
        }
    }

    fun playVictory() {
        scope.launch {
            // Celebration major progression
            playSine(523.25f, 250) // C5
            kotlinx.coroutines.delay(50)
            playSine(659.25f, 250) // E5
            kotlinx.coroutines.delay(50)
            playSine(783.99f, 250) // G5
            kotlinx.coroutines.delay(50)
            playSine(1046.50f, 600) // C6
        }
    }

    private fun playSine(frequency: Float, durationMs: Int) {
        try {
            val numSamples = (durationMs * SAMPLE_RATE / 1000)
            val samples = ShortArray(numSamples)
            
            for (i in 0 until numSamples) {
                val t = i.toFloat() / SAMPLE_RATE
                val angle = 2.0 * Math.PI * frequency * t
                // Add a subtle fade out to prevent popping
                val fadeOut = if (i > numSamples - 1000) {
                    (numSamples - i).toFloat() / 1000f
                } else 1.0f
                samples[i] = (sin(angle) * Short.MAX_VALUE * 0.5f * fadeOut).toInt().toShort()
            }

            playRawPcm(samples)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun playToneSweep(startFreq: Float, endFreq: Float, durationMs: Int) {
        try {
            val numSamples = (durationMs * SAMPLE_RATE / 1000)
            val samples = ShortArray(numSamples)
            
            for (i in 0 until numSamples) {
                val progress = i.toFloat() / numSamples
                val currentFreq = startFreq + (endFreq - startFreq) * progress
                val t = i.toFloat() / SAMPLE_RATE
                val angle = 2.0 * Math.PI * currentFreq * t
                // Linear fade out
                val fadeOut = (numSamples - i).toFloat() / numSamples
                samples[i] = (sin(angle) * Short.MAX_VALUE * 0.4f * fadeOut).toInt().toShort()
            }

            playRawPcm(samples)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun playRawPcm(samples: ShortArray) {
        try {
            val bufferSize = samples.size * 2
            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            if (audioTrack.state == AudioTrack.STATE_INITIALIZED) {
                audioTrack.write(samples, 0, samples.size)
                audioTrack.play()
                
                // Let it play, then clean up automatically
                scope.launch {
                    kotlinx.coroutines.delay((samples.size.toFloat() / SAMPLE_RATE * 1000).toLong() + 100)
                    try {
                        audioTrack.stop()
                        audioTrack.release()
                    } catch (e: Exception) {
                        // Ignore silent failure if stopped early
                    }
                }
            } else {
                audioTrack.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
