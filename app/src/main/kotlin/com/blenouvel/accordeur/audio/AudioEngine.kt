package com.blenouvel.accordeur.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

/** Causes d'échec de la capture. */
enum class EngineError { PERMISSION, UNAVAILABLE, READ_FAILED }

/** État du moteur audio. */
sealed interface EngineState {
    data object Idle : EngineState
    data class Running(val sampleRate: Int, val unprocessed: Boolean) : EngineState
    data class Failed(val error: EngineError) : EngineState
}

/**
 * Capture micro temps réel : un seul [AudioRecord] (source UNPROCESSED si l'appareil la
 * propose, sinon VOICE_RECOGNITION — moins traitée que MIC), 48 kHz mono en PCM float.
 *
 * Un thread dédié (priorité audio) lit des blocs de [PitchDetector.HOP] échantillons et les
 * passe au [TunerProcessor] ; l'analyse (~0,5 ms) tient largement dans les 43 ms d'un bloc et le
 * tampon interne d'AudioRecord (≥ 0,7 s) absorbe les à-coups. Les instantanés sont publiés dans
 * [frames]. Démarrer en `onResume`, arrêter en `onPause` : le micro est libéré en arrière-plan.
 *
 * Note : `AudioRecord` n'expose pas de mode « basse latence » (réservé à AAudio) ; inutile ici,
 * la fenêtre d'analyse de 171 ms domine de toute façon la latence.
 */
class AudioEngine(context: Context) {
    private val appContext = context.applicationContext

    private val _frames = MutableStateFlow<TunerFrame?>(null)
    val frames: StateFlow<TunerFrame?> = _frames.asStateFlow()

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    @Volatile
    var mode: TunerMode = TunerMode.MONO
        set(value) {
            field = value
            processor?.mode = value
        }

    @Volatile
    var targets: TunerTargets = TunerTargets.NONE
        set(value) {
            field = value
            processor?.targets = value
        }

    @Volatile
    private var muteUntil = 0L

    @Volatile
    private var running = false

    @Volatile
    private var processor: TunerProcessor? = null

    private var thread: Thread? = null

    /** Suspend l'analyse pendant [millis] ms (lecture du son de référence). */
    fun muteFor(millis: Long) {
        muteUntil = SystemClock.elapsedRealtime() + millis
    }

    fun unmute() {
        muteUntil = 0L
    }

    @Synchronized
    fun start() {
        if (thread?.isAlive == true) return
        val granted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            _state.value = EngineState.Failed(EngineError.PERMISSION)
            return
        }
        running = true
        thread = Thread(::captureLoop, "accordeur-audio").also { it.start() }
    }

    @Synchronized
    fun stop() {
        running = false
        val t = thread ?: return
        thread = null
        // La lecture en cours se termine en ≤ 1 bloc (43 ms).
        try {
            t.join(STOP_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        _frames.value = null
        if (_state.value is EngineState.Running) _state.value = EngineState.Idle
    }

    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val opened = openRecord()
        if (opened == null) {
            _state.value = EngineState.Failed(EngineError.UNAVAILABLE)
            running = false
            return
        }
        val (record, unprocessed) = opened
        val sampleRate = record.sampleRate
        val floatInput = record.audioFormat == AudioFormat.ENCODING_PCM_FLOAT
        val processor = TunerProcessor(sampleRate).also {
            it.mode = mode
            it.targets = targets
        }
        this.processor = processor
        val samples = FloatArray(PitchDetector.HOP)
        val shorts = if (floatInput) null else ShortArray(PitchDetector.HOP)
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                _state.value = EngineState.Failed(EngineError.UNAVAILABLE)
                return
            }
            _state.value = EngineState.Running(sampleRate, unprocessed)
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
                    _state.value = EngineState.Failed(EngineError.READ_FAILED)
                    break
                }
                processor.muted = SystemClock.elapsedRealtime() < muteUntil
                val frame = processor.process(samples, count)
                if (frame != null) _frames.value = frame
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "Capture interrompue", e)
            _state.value = EngineState.Failed(EngineError.READ_FAILED)
        } finally {
            try {
                record.stop()
            } catch (_: IllegalStateException) {
            }
            record.release()
            this.processor = null
        }
    }

    /** Ouvre le micro : UNPROCESSED → VOICE_RECOGNITION → MIC ; 48 kHz puis 44,1 kHz ; float puis 16 bits. */
    @SuppressLint("MissingPermission") // permission vérifiée dans start()
    private fun openRecord(): Pair<AudioRecord, Boolean>? {
        val audioManager = appContext.getSystemService(AudioManager::class.java)
        val unprocessedSupported =
            audioManager?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val sources = buildList {
            if (unprocessedSupported) add(MediaRecorder.AudioSource.UNPROCESSED)
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            add(MediaRecorder.AudioSource.MIC)
        }
        for (source in sources) {
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
                            .setBufferSizeInBytes(max(minBuffer, PitchDetector.WINDOW * bytesPerSample * 4))
                            .build()
                    } catch (e: Exception) {
                        Log.i(TAG, "Configuration refusée : source=$source $rate Hz encodage=$encoding", e)
                        null
                    }
                    if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                        return record to (source == MediaRecorder.AudioSource.UNPROCESSED)
                    }
                    record?.release()
                }
            }
        }
        return null
    }

    private companion object {
        const val TAG = "AudioEngine"
        const val STOP_TIMEOUT_MS = 500L
        val SAMPLE_RATES = intArrayOf(PitchDetector.SAMPLE_RATE, 44_100)
        val ENCODINGS = intArrayOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)
    }
}
