package com.blenouvel.accordeur.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlin.math.max

/**
 * Écoute du micro sur la page Métronome, dédiée au suivi du tempo : un [AudioRecord] à part (le
 * métronome reste ainsi auto-suffisant et peut sonner en même temps), lu par blocs courts dans un
 * [OnsetDetector]. Chaque attaque est publiée dans [onsets] horodatée sur l'horloge monotone du
 * système ([SystemClock.elapsedRealtimeNanos]), la même que celle des temps du métronome.
 *
 * Aucune analyse de hauteur : uniquement les attaques. Démarrer/arrêter au gré du bouton Lecture et
 * de l'option « suivi du tempo » ; sans permission micro, [start] ne fait rien.
 */
class TempoListener(context: Context) {
    private val appContext = context.applicationContext

    private val _onsets = MutableSharedFlow<Long>(extraBufferCapacity = 64)
    /** Instants (ns, [SystemClock.elapsedRealtimeNanos]) des attaques détectées. */
    val onsets: SharedFlow<Long> = _onsets

    @Volatile
    private var running = false
    private var thread: Thread? = null

    @Synchronized
    fun start() {
        if (thread?.isAlive == true) return
        val granted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return
        running = true
        thread = Thread(::loop, "accordeur-tempo").also { it.start() }
    }

    @Synchronized
    fun stop() {
        running = false
        val t = thread ?: return
        thread = null
        try {
            t.join(STOP_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val record = openRecord() ?: run {
            running = false
            return
        }
        val sampleRate = record.sampleRate
        val floatInput = record.audioFormat == AudioFormat.ENCODING_PCM_FLOAT
        val detector = OnsetDetector(sampleRate)
        val samples = FloatArray(READ)
        val shorts = if (floatInput) null else ShortArray(READ)
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return
            // Ancre : l'échantillon 0 correspond à cet instant (la latence d'entrée est une constante,
            // absorbée par l'offset de calibration, comme la latence de sortie du métronome).
            val startNanos = SystemClock.elapsedRealtimeNanos()
            while (running) {
                val count = if (shorts == null) {
                    record.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                } else {
                    val n = record.read(shorts, 0, shorts.size, AudioRecord.READ_BLOCKING)
                    for (i in 0 until n) samples[i] = shorts[i] / 32768f
                    n
                }
                if (count < 0) {
                    Log.w(TAG, "AudioRecord.read : $count")
                    break
                }
                detector.process(samples, count) { timeSec ->
                    _onsets.tryEmit(startNanos + (timeSec * 1_000_000_000.0).toLong())
                }
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "Écoute tempo interrompue", e)
        } finally {
            try {
                record.stop()
            } catch (_: IllegalStateException) {
            }
            record.release()
        }
    }

    /** Ouvre le micro en préférant les sources qui préservent le transitoire (pas d'AGC/réduction). */
    @SuppressLint("MissingPermission") // permission vérifiée dans start()
    private fun openRecord(): AudioRecord? {
        for (source in SOURCES) {
            for (rate in SAMPLE_RATES) {
                for (encoding in ENCODINGS) {
                    val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, encoding)
                    if (minBuffer <= 0) continue
                    val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                    val record = try {
                        AudioRecord.Builder()
                            .setAudioSource(source)
                            .setAudioFormat(
                                AudioFormat.Builder()
                                    .setEncoding(encoding)
                                    .setSampleRate(rate)
                                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                    .build(),
                            )
                            .setBufferSizeInBytes(max(minBuffer, READ * bytesPerSample * 8))
                            .build()
                    } catch (e: Exception) {
                        Log.i(TAG, "Config refusée : source=$source $rate Hz enc=$encoding", e)
                        null
                    }
                    if (record != null && record.state == AudioRecord.STATE_INITIALIZED) return record
                    record?.release()
                }
            }
        }
        return null
    }

    private companion object {
        const val TAG = "TempoListener"
        const val STOP_TIMEOUT_MS = 400L

        /** Blocs courts : faible latence de réaction, plusieurs hops d'onset par lecture. */
        const val READ = 1024
        val SOURCES = intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
        val SAMPLE_RATES = intArrayOf(48_000, 44_100)
        val ENCODINGS = intArrayOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)
    }
}
