package com.blenouvel.accordeur.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.math.max

/** Causes d'échec de la capture. */
enum class EngineError { PERMISSION, UNAVAILABLE, READ_FAILED }

/** VOICE_PERFORMANCE n'existe qu'à partir d'Android 10. */
val MicSource.isAvailable: Boolean
    get() = this != MicSource.VOICE_PERFORMANCE || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

/** État du moteur audio. */
sealed interface EngineState {
    data object Idle : EngineState

    /** [source] : source réellement ouverte ; [unprocessedDeclared] : UNPROCESSED annoncé par le téléphone. */
    data class Running(val sampleRate: Int, val source: MicSource, val unprocessedDeclared: Boolean) : EngineState

    data class Failed(val error: EngineError) : EngineState
}

/**
 * Capture micro temps réel : un seul [AudioRecord] (source choisie par [source], voir
 * [MicSource]), 48 kHz mono en PCM float. Les traitements du téléphone accessibles aux
 * applications (réduction de bruit, gain automatique, annulation d'écho) sont désactivés.
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

    /** Banque de sons de test : enregistre pendant l'écoute quand c'est activé. */
    val recorder = SoundRecorder(File(appContext.filesDir, SoundRecorder.DIRECTORY))

    private val appVersion: String by lazy {
        try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.packageManager.getPackageInfo(appContext.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            }
            "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
        } catch (_: PackageManager.NameNotFoundException) {
            "?"
        }
    }

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

    /** Page Spectre : spectre et notes du signal brut au lieu de l'accordage. */
    @Volatile
    var spectrum: Boolean = false
        set(value) {
            field = value
            processor?.spectrum = value
        }

    /** Source demandée ; la changer relance la capture si elle tourne. */
    @Volatile
    var source: MicSource = MicSource.AUTO
        set(value) {
            if (field == value) return
            field = value
            restartIfRunning()
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

    @Synchronized
    private fun restartIfRunning() {
        if (thread?.isAlive != true) return
        stop()
        start()
    }

    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val opened = openRecord(source)
        if (opened == null) {
            _state.value = EngineState.Failed(EngineError.UNAVAILABLE)
            running = false
            return
        }
        val record = opened.record
        val effects = disableEffects(record.audioSessionId)
        val sampleRate = record.sampleRate
        val floatInput = record.audioFormat == AudioFormat.ENCODING_PCM_FLOAT
        val processor = TunerProcessor(sampleRate).also {
            it.mode = mode
            it.targets = targets
            it.spectrum = spectrum
        }
        this.processor = processor
        val samples = FloatArray(PitchDetector.HOP)
        val shorts = if (floatInput) null else ShortArray(PitchDetector.HOP)
        recorder.start(
            CaptureFormat(
                sampleRate = sampleRate,
                floatSamples = floatInput,
                source = opened.source,
                requestedSource = source,
                unprocessedDeclared = opened.unprocessedDeclared,
                device = "${Build.MANUFACTURER} ${Build.MODEL}",
                android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                appVersion = appVersion,
            ),
        )
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                _state.value = EngineState.Failed(EngineError.UNAVAILABLE)
                return
            }
            _state.value = EngineState.Running(sampleRate, opened.source, opened.unprocessedDeclared)
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
                val muted = SystemClock.elapsedRealtime() < muteUntil
                processor.muted = muted
                val frame = processor.process(samples, count)
                recorder.offer(samples, count, frame, muted)
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
            for (effect in effects) effect.release()
            recorder.stop()
            this.processor = null
        }
    }

    private class Opened(val record: AudioRecord, val source: MicSource, val unprocessedDeclared: Boolean)

    /**
     * Ouvre le micro : source demandée, puis l'ordre automatique (UNPROCESSED s'il est annoncé,
     * VOICE_PERFORMANCE, VOICE_RECOGNITION, MIC) ; 48 kHz puis 44,1 kHz ; float puis 16 bits.
     */
    @SuppressLint("MissingPermission") // permission vérifiée dans start()
    private fun openRecord(requested: MicSource): Opened? {
        val audioManager = appContext.getSystemService(AudioManager::class.java)
        val declared = audioManager?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val order = buildList {
            if (requested != MicSource.AUTO) add(requested)
            if (declared) add(MicSource.UNPROCESSED)
            add(MicSource.VOICE_PERFORMANCE)
            add(MicSource.VOICE_RECOGNITION)
            add(MicSource.MIC)
        }.distinct().filter { it.isAvailable }
        for (source in order) {
            for (rate in SAMPLE_RATES) {
                for (encoding in ENCODINGS) {
                    val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, encoding)
                    if (minBuffer <= 0) continue
                    val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                    val record = try {
                        AudioRecord.Builder()
                            .setAudioSource(source.androidSource())
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
                        return Opened(record, source, declared)
                    }
                    record?.release()
                }
            }
        }
        return null
    }

    /** Désactive les traitements du téléphone attachés à la capture, quand ils sont accessibles. */
    private fun disableEffects(sessionId: Int): List<AudioEffect> {
        val effects = ArrayList<AudioEffect>()
        fun disable(create: () -> AudioEffect?) {
            try {
                val effect = create() ?: return
                effect.setEnabled(false)
                effects += effect
            } catch (e: RuntimeException) {
                Log.i(TAG, "Traitement non désactivable", e)
            }
        }
        if (NoiseSuppressor.isAvailable()) disable { NoiseSuppressor.create(sessionId) }
        if (AutomaticGainControl.isAvailable()) disable { AutomaticGainControl.create(sessionId) }
        if (AcousticEchoCanceler.isAvailable()) disable { AcousticEchoCanceler.create(sessionId) }
        return effects
    }

    @SuppressLint("InlinedApi") // VOICE_PERFORMANCE filtré par MicSource.isAvailable (Android 10+)
    private fun MicSource.androidSource(): Int = when (this) {
        MicSource.UNPROCESSED -> MediaRecorder.AudioSource.UNPROCESSED
        MicSource.VOICE_PERFORMANCE -> MediaRecorder.AudioSource.VOICE_PERFORMANCE
        MicSource.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        MicSource.CAMCORDER -> MediaRecorder.AudioSource.CAMCORDER
        MicSource.MIC, MicSource.AUTO -> MediaRecorder.AudioSource.MIC
    }

    private companion object {
        const val TAG = "AudioEngine"
        const val STOP_TIMEOUT_MS = 500L
        val SAMPLE_RATES = intArrayOf(PitchDetector.SAMPLE_RATE, 44_100)
        val ENCODINGS = intArrayOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)
    }
}
