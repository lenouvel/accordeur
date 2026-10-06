package com.blenouvel.accordeur.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.blenouvel.accordeur.audio.MicSource
import com.blenouvel.accordeur.audio.isAvailable
import com.blenouvel.accordeur.audio.TunerMode
import com.blenouvel.accordeur.model.ClickSound
import com.blenouvel.accordeur.model.CustomTuningCodec
import com.blenouvel.accordeur.model.FretLabels
import com.blenouvel.accordeur.model.FretboardMap
import com.blenouvel.accordeur.model.MetronomeConfig
import com.blenouvel.accordeur.model.MetronomeRange
import com.blenouvel.accordeur.model.Notation
import com.blenouvel.accordeur.model.NoteMapper
import com.blenouvel.accordeur.model.Presets
import com.blenouvel.accordeur.model.ScaleCatalog
import com.blenouvel.accordeur.model.SilentMeasures
import com.blenouvel.accordeur.model.Subdivision
import com.blenouvel.accordeur.model.TempoAutomation
import com.blenouvel.accordeur.model.Tuning
import com.blenouvel.accordeur.model.VariationLaw
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class ThemeMode { DARK, LIGHT, SYSTEM }

/** AUTO : note chromatique la plus proche ; MANUAL : toujours une corde sélectionnée. */
enum class DetectionMode { AUTO, MANUAL }

/** Réglages persistés. Les valeurs par défaut sont celles de la spécification. */
data class Settings(
    val a4: Double = NoteMapper.DEFAULT_A4,
    val toleranceCents: Int = DEFAULT_TOLERANCE,
    val notation: Notation = Notation.FRENCH,
    val detectionMode: DetectionMode = DetectionMode.AUTO,
    val tunerMode: TunerMode = TunerMode.MONO,
    val theme: ThemeMode = ThemeMode.DARK,
    val dynamicColor: Boolean = false,
    val haptics: Boolean = true,
    val keepScreenOn: Boolean = true,
    /** Page Spectre : mettre en évidence la guitare (série de partiels) par rapport au bruit. */
    val spectrumHighlight: Boolean = true,
    /** Source micro (AUTO : la moins traitée disponible). */
    val micSource: MicSource = MicSource.AUTO,
    /** Banque de sons de test : garder le son brut de chaque note jouée (désactivable). */
    val recordBank: Boolean = true,
    val tuningId: String = Presets.DEFAULT.id,
    val customTunings: List<Tuning> = emptyList(),
    /** Derniers accordages choisis (identifiants, du plus récent au plus ancien). */
    val recentTuningIds: List<String> = emptyList(),
    /** Vue « Gammes » : fondamentale (classe de hauteur 0 = Do), gamme, cases, étiquettes, gaucher. */
    val scaleRoot: Int = 0,
    val scaleId: String = ScaleCatalog.DEFAULT.id,
    val scaleFrets: Int = FretboardMap.FRET_COUNTS.first(),
    val scaleLabels: FretLabels = FretLabels.NOTES,
    val leftHanded: Boolean = false,
    /** Mode interactif Gammes : focus sur la case la plus proche (true) ou toutes les positions (false). */
    val scaleFocusNearest: Boolean = false,
    /** Dernières gammes choisies (identifiants, du plus récent au plus ancien). */
    val recentScaleIds: List<String> = emptyList(),
    // --- Métronome (champs plats ; assemblés dans [metronome]). ---
    val metroBpm: Int = MetronomeRange.DEFAULT_BPM,
    val metroBeats: Int = 4,
    val metroSubdivision: Subdivision = Subdivision.QUARTER,
    val metroSound: ClickSound = ClickSound.CLICK,
    val metroAccentFirst: Boolean = true,
    val metroCountInBars: Int = 0,
    val metroSilentEnabled: Boolean = false,
    val metroSilentPlay: Int = 1,
    val metroSilentMute: Int = 1,
    val metroAutoEnabled: Boolean = false,
    val metroAutoStart: Int = 60,
    val metroAutoTarget: Int = 120,
    val metroAutoStep: Int = 5,
    val metroAutoStepBars: Int = 4,
    val metroAutoLoop: Boolean = false,
    val metroAutoLaw: VariationLaw = VariationLaw.LINEAR,
) {
    /** Accordage courant (repli sur Standard 6 cordes si l'identifiant n'existe plus). */
    val tuning: Tuning
        get() = customTunings.firstOrNull { it.id == tuningId } ?: Presets.byId(tuningId) ?: Presets.DEFAULT

    /** Configuration du métronome assemblée depuis les champs plats. */
    val metronome: MetronomeConfig
        get() = MetronomeConfig(
            bpm = metroBpm,
            beatsPerMeasure = metroBeats,
            subdivision = metroSubdivision,
            sound = metroSound,
            accentFirst = metroAccentFirst,
            countInBars = metroCountInBars,
            silent = SilentMeasures(metroSilentEnabled, metroSilentPlay, metroSilentMute),
            automation = TempoAutomation(
                enabled = metroAutoEnabled,
                startBpm = metroAutoStart,
                targetBpm = metroAutoTarget,
                stepBpm = metroAutoStep,
                stepBars = metroAutoStepBars,
                loop = metroAutoLoop,
                law = metroAutoLaw,
            ),
        ).sanitized()

    companion object {
        const val DEFAULT_TOLERANCE = 5
        const val MIN_TOLERANCE = 1
        const val MAX_TOLERANCE = 15

        /** En deçà, la corde est « parfaitement » juste. */
        const val PERFECT_CENTS = 3.0
    }
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Persistance des réglages (DataStore Preferences). */
class SettingsStore(context: Context) {
    private val store = context.applicationContext.settingsDataStore

    val settings: Flow<Settings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }

    suspend fun setA4(value: Double) = edit { it[A4] = value.coerceIn(NoteMapper.MIN_A4, NoteMapper.MAX_A4) }

    suspend fun setTolerance(cents: Int) =
        edit { it[TOLERANCE] = cents.coerceIn(Settings.MIN_TOLERANCE, Settings.MAX_TOLERANCE) }

    suspend fun setNotation(value: Notation) = edit { it[NOTATION] = value.name }

    suspend fun setDetectionMode(value: DetectionMode) = edit { it[DETECTION_MODE] = value.name }

    suspend fun setTunerMode(value: TunerMode) = edit { it[TUNER_MODE] = value.name }

    suspend fun setTheme(value: ThemeMode) = edit { it[THEME] = value.name }

    suspend fun setDynamicColor(value: Boolean) = edit { it[DYNAMIC_COLOR] = value }

    suspend fun setHaptics(value: Boolean) = edit { it[HAPTICS] = value }

    suspend fun setKeepScreenOn(value: Boolean) = edit { it[KEEP_SCREEN_ON] = value }

    suspend fun setSpectrumHighlight(value: Boolean) = edit { it[SPECTRUM_HIGHLIGHT] = value }

    suspend fun setMicSource(value: MicSource) = edit { it[MIC_SOURCE] = value.name }

    suspend fun setRecordBank(value: Boolean) = edit { it[RECORD_BANK] = value }

    suspend fun selectTuning(id: String) = edit { prefs ->
        prefs[TUNING_ID] = id
        prefs[RECENT_TUNINGS] = pushRecent(prefs[RECENT_TUNINGS], id)
    }

    /** Ajoute [id] en tête des récents (sans doublon), plafonné à [RECENT_MAX]. */
    private fun pushRecent(current: String?, id: String): String =
        (listOf(id) + (current?.split(',') ?: emptyList()))
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(RECENT_MAX).joinToString(",")

    suspend fun setScaleRoot(pitchClass: Int) = edit { it[SCALE_ROOT] = pitchClass.mod(12) }

    suspend fun setScale(id: String) = edit { prefs ->
        prefs[SCALE_ID] = id
        prefs[RECENT_SCALES] = pushRecent(prefs[RECENT_SCALES], id)
    }

    suspend fun setScaleFrets(frets: Int) = edit { it[SCALE_FRETS] = frets }

    suspend fun setScaleLabels(value: FretLabels) = edit { it[SCALE_LABELS] = value.name }

    suspend fun setLeftHanded(value: Boolean) = edit { it[LEFT_HANDED] = value }

    suspend fun setScaleFocusNearest(value: Boolean) = edit { it[SCALE_FOCUS_NEAREST] = value }

    suspend fun setMetroBpm(value: Int) =
        edit { it[METRO_BPM] = value.coerceIn(MetronomeRange.MIN_BPM, MetronomeRange.MAX_BPM) }

    suspend fun setMetroBeats(value: Int) =
        edit { it[METRO_BEATS] = value.coerceIn(MetronomeRange.MIN_BEATS, MetronomeRange.MAX_BEATS) }

    suspend fun setMetroSubdivision(value: Subdivision) = edit { it[METRO_SUBDIVISION] = value.name }

    suspend fun setMetroSound(value: ClickSound) = edit { it[METRO_SOUND] = value.name }

    suspend fun setMetroAccentFirst(value: Boolean) = edit { it[METRO_ACCENT] = value }

    suspend fun setMetroCountIn(value: Int) =
        edit { it[METRO_COUNT_IN] = value.coerceIn(0, MetronomeRange.MAX_COUNT_IN) }

    suspend fun setMetroSilent(value: SilentMeasures) = edit {
        it[METRO_SILENT_ENABLED] = value.enabled
        it[METRO_SILENT_PLAY] = value.playBars.coerceIn(1, MetronomeRange.MAX_SILENT_BARS)
        it[METRO_SILENT_MUTE] = value.muteBars.coerceIn(1, MetronomeRange.MAX_SILENT_BARS)
    }

    suspend fun setMetroAutomation(value: TempoAutomation) = edit {
        it[METRO_AUTO_ENABLED] = value.enabled
        it[METRO_AUTO_START] = value.startBpm.coerceIn(MetronomeRange.MIN_BPM, MetronomeRange.MAX_BPM)
        it[METRO_AUTO_TARGET] = value.targetBpm.coerceIn(MetronomeRange.MIN_BPM, MetronomeRange.MAX_BPM)
        it[METRO_AUTO_STEP] = value.stepBpm.coerceIn(1, 60)
        it[METRO_AUTO_STEPBARS] = value.stepBars.coerceIn(1, 32)
        it[METRO_AUTO_LOOP] = value.loop
        it[METRO_AUTO_LAW] = value.law.name
    }


    /** Ajoute ou remplace (même identifiant) un accordage personnalisé, puis le sélectionne. */
    suspend fun saveCustomTuning(tuning: Tuning) = edit { prefs ->
        val current = CustomTuningCodec.decode(prefs[CUSTOM_TUNINGS])
        val updated = if (current.any { it.id == tuning.id }) {
            current.map { if (it.id == tuning.id) tuning else it }
        } else {
            current + tuning
        }
        prefs[CUSTOM_TUNINGS] = CustomTuningCodec.encode(updated)
        prefs[TUNING_ID] = tuning.id
        prefs[RECENT_TUNINGS] = pushRecent(prefs[RECENT_TUNINGS], tuning.id)
    }

    suspend fun deleteCustomTuning(id: String) = edit { prefs ->
        val remaining = CustomTuningCodec.decode(prefs[CUSTOM_TUNINGS]).filterNot { it.id == id }
        prefs[CUSTOM_TUNINGS] = CustomTuningCodec.encode(remaining)
        prefs[RECENT_TUNINGS] = (prefs[RECENT_TUNINGS]?.split(',') ?: emptyList())
            .map { it.trim() }.filter { it.isNotEmpty() && it != id }.joinToString(",")
        if (prefs[TUNING_ID] == id) prefs[TUNING_ID] = Presets.DEFAULT.id
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        store.edit { block(it) }
    }

    private fun Preferences.toSettings(): Settings {
        val defaults = Settings()
        return Settings(
            a4 = this[A4] ?: defaults.a4,
            toleranceCents = this[TOLERANCE] ?: defaults.toleranceCents,
            notation = enumOf(this[NOTATION], defaults.notation),
            detectionMode = enumOf(this[DETECTION_MODE], defaults.detectionMode),
            tunerMode = enumOf(this[TUNER_MODE], defaults.tunerMode),
            theme = enumOf(this[THEME], defaults.theme),
            dynamicColor = this[DYNAMIC_COLOR] ?: defaults.dynamicColor,
            haptics = this[HAPTICS] ?: defaults.haptics,
            keepScreenOn = this[KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
            spectrumHighlight = this[SPECTRUM_HIGHLIGHT] ?: defaults.spectrumHighlight,
            micSource = enumOf(this[MIC_SOURCE], defaults.micSource).takeIf { it.isAvailable } ?: defaults.micSource,
            recordBank = this[RECORD_BANK] ?: defaults.recordBank,
            tuningId = this[TUNING_ID] ?: defaults.tuningId,
            customTunings = CustomTuningCodec.decode(this[CUSTOM_TUNINGS]),
            recentTuningIds = this[RECENT_TUNINGS]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: defaults.recentTuningIds,
            scaleRoot = (this[SCALE_ROOT] ?: defaults.scaleRoot).mod(12),
            scaleId = ScaleCatalog.byId(this[SCALE_ID]).id,
            scaleFrets = this[SCALE_FRETS]?.takeIf { it in FretboardMap.FRET_COUNTS } ?: defaults.scaleFrets,
            scaleLabels = enumOf(this[SCALE_LABELS], defaults.scaleLabels),
            leftHanded = this[LEFT_HANDED] ?: defaults.leftHanded,
            scaleFocusNearest = this[SCALE_FOCUS_NEAREST] ?: defaults.scaleFocusNearest,
            recentScaleIds = this[RECENT_SCALES]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: defaults.recentScaleIds,
            metroBpm = this[METRO_BPM] ?: defaults.metroBpm,
            metroBeats = this[METRO_BEATS] ?: defaults.metroBeats,
            metroSubdivision = enumOf(this[METRO_SUBDIVISION], defaults.metroSubdivision),
            metroSound = enumOf(this[METRO_SOUND], defaults.metroSound),
            metroAccentFirst = this[METRO_ACCENT] ?: defaults.metroAccentFirst,
            metroCountInBars = this[METRO_COUNT_IN] ?: defaults.metroCountInBars,
            metroSilentEnabled = this[METRO_SILENT_ENABLED] ?: defaults.metroSilentEnabled,
            metroSilentPlay = this[METRO_SILENT_PLAY] ?: defaults.metroSilentPlay,
            metroSilentMute = this[METRO_SILENT_MUTE] ?: defaults.metroSilentMute,
            metroAutoEnabled = this[METRO_AUTO_ENABLED] ?: defaults.metroAutoEnabled,
            metroAutoStart = this[METRO_AUTO_START] ?: defaults.metroAutoStart,
            metroAutoTarget = this[METRO_AUTO_TARGET] ?: defaults.metroAutoTarget,
            metroAutoStep = this[METRO_AUTO_STEP] ?: defaults.metroAutoStep,
            metroAutoStepBars = this[METRO_AUTO_STEPBARS] ?: defaults.metroAutoStepBars,
            metroAutoLoop = this[METRO_AUTO_LOOP] ?: defaults.metroAutoLoop,
            metroAutoLaw = enumOf(this[METRO_AUTO_LAW], defaults.metroAutoLaw),
        )
    }

    /** Lecture d'enum sans réflexion (robuste à R8 et aux valeurs inconnues). */
    private inline fun <reified E : Enum<E>> enumOf(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private companion object {
        val A4 = doublePreferencesKey("a4")
        val TOLERANCE = intPreferencesKey("tolerance_cents")
        val NOTATION = stringPreferencesKey("notation")
        val DETECTION_MODE = stringPreferencesKey("detection_mode")
        val TUNER_MODE = stringPreferencesKey("tuner_mode")
        val THEME = stringPreferencesKey("theme")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val HAPTICS = booleanPreferencesKey("haptics")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val SPECTRUM_HIGHLIGHT = booleanPreferencesKey("spectrum_highlight")
        val MIC_SOURCE = stringPreferencesKey("mic_source")
        val RECORD_BANK = booleanPreferencesKey("record_bank")
        val TUNING_ID = stringPreferencesKey("tuning_id")
        val CUSTOM_TUNINGS = stringPreferencesKey("custom_tunings")
        val RECENT_TUNINGS = stringPreferencesKey("recent_tunings")
        const val RECENT_MAX = 5
        val SCALE_ROOT = intPreferencesKey("scale_root")
        val SCALE_ID = stringPreferencesKey("scale_id")
        val SCALE_FRETS = intPreferencesKey("scale_frets")
        val SCALE_LABELS = stringPreferencesKey("scale_labels")
        val LEFT_HANDED = booleanPreferencesKey("left_handed")
        val SCALE_FOCUS_NEAREST = booleanPreferencesKey("scale_focus_nearest")
        val RECENT_SCALES = stringPreferencesKey("recent_scales")
        val METRO_BPM = intPreferencesKey("metro_bpm")
        val METRO_BEATS = intPreferencesKey("metro_beats")
        val METRO_SUBDIVISION = stringPreferencesKey("metro_subdivision")
        val METRO_SOUND = stringPreferencesKey("metro_sound")
        val METRO_ACCENT = booleanPreferencesKey("metro_accent")
        val METRO_COUNT_IN = intPreferencesKey("metro_count_in")
        val METRO_SILENT_ENABLED = booleanPreferencesKey("metro_silent_enabled")
        val METRO_SILENT_PLAY = intPreferencesKey("metro_silent_play")
        val METRO_SILENT_MUTE = intPreferencesKey("metro_silent_mute")
        val METRO_AUTO_ENABLED = booleanPreferencesKey("metro_auto_enabled")
        val METRO_AUTO_START = intPreferencesKey("metro_auto_start")
        val METRO_AUTO_TARGET = intPreferencesKey("metro_auto_target")
        val METRO_AUTO_STEP = intPreferencesKey("metro_auto_step")
        val METRO_AUTO_STEPBARS = intPreferencesKey("metro_auto_stepbars")
        val METRO_AUTO_LOOP = booleanPreferencesKey("metro_auto_loop")
        val METRO_AUTO_LAW = stringPreferencesKey("metro_auto_law")
    }
}
