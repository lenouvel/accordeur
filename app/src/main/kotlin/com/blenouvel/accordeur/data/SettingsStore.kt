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
import com.blenouvel.accordeur.model.CustomTuningCodec
import com.blenouvel.accordeur.model.FretLabels
import com.blenouvel.accordeur.model.FretboardMap
import com.blenouvel.accordeur.model.Notation
import com.blenouvel.accordeur.model.NoteMapper
import com.blenouvel.accordeur.model.Presets
import com.blenouvel.accordeur.model.ScaleCatalog
import com.blenouvel.accordeur.model.Tuning
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
    /** Source micro (AUTO : la moins traitée disponible). */
    val micSource: MicSource = MicSource.AUTO,
    /** Banque de sons de test : garder le son brut de chaque note jouée (désactivable). */
    val recordBank: Boolean = true,
    val tuningId: String = Presets.DEFAULT.id,
    val customTunings: List<Tuning> = emptyList(),
    /** Vue « Gammes » : fondamentale (classe de hauteur 0 = Do), gamme, cases, étiquettes, gaucher. */
    val scaleRoot: Int = 0,
    val scaleId: String = ScaleCatalog.DEFAULT.id,
    val scaleFrets: Int = FretboardMap.FRET_COUNTS.first(),
    val scaleLabels: FretLabels = FretLabels.NOTES,
    val leftHanded: Boolean = false,
) {
    /** Accordage courant (repli sur Standard 6 cordes si l'identifiant n'existe plus). */
    val tuning: Tuning
        get() = customTunings.firstOrNull { it.id == tuningId } ?: Presets.byId(tuningId) ?: Presets.DEFAULT

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

    suspend fun setMicSource(value: MicSource) = edit { it[MIC_SOURCE] = value.name }

    suspend fun setRecordBank(value: Boolean) = edit { it[RECORD_BANK] = value }

    suspend fun selectTuning(id: String) = edit { it[TUNING_ID] = id }

    suspend fun setScaleRoot(pitchClass: Int) = edit { it[SCALE_ROOT] = pitchClass.mod(12) }

    suspend fun setScale(id: String) = edit { it[SCALE_ID] = id }

    suspend fun setScaleFrets(frets: Int) = edit { it[SCALE_FRETS] = frets }

    suspend fun setScaleLabels(value: FretLabels) = edit { it[SCALE_LABELS] = value.name }

    suspend fun setLeftHanded(value: Boolean) = edit { it[LEFT_HANDED] = value }

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
    }

    suspend fun deleteCustomTuning(id: String) = edit { prefs ->
        val remaining = CustomTuningCodec.decode(prefs[CUSTOM_TUNINGS]).filterNot { it.id == id }
        prefs[CUSTOM_TUNINGS] = CustomTuningCodec.encode(remaining)
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
            micSource = enumOf(this[MIC_SOURCE], defaults.micSource).takeIf { it.isAvailable } ?: defaults.micSource,
            recordBank = this[RECORD_BANK] ?: defaults.recordBank,
            tuningId = this[TUNING_ID] ?: defaults.tuningId,
            customTunings = CustomTuningCodec.decode(this[CUSTOM_TUNINGS]),
            scaleRoot = (this[SCALE_ROOT] ?: defaults.scaleRoot).mod(12),
            scaleId = ScaleCatalog.byId(this[SCALE_ID]).id,
            scaleFrets = this[SCALE_FRETS]?.takeIf { it in FretboardMap.FRET_COUNTS } ?: defaults.scaleFrets,
            scaleLabels = enumOf(this[SCALE_LABELS], defaults.scaleLabels),
            leftHanded = this[LEFT_HANDED] ?: defaults.leftHanded,
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
        val MIC_SOURCE = stringPreferencesKey("mic_source")
        val RECORD_BANK = booleanPreferencesKey("record_bank")
        val TUNING_ID = stringPreferencesKey("tuning_id")
        val CUSTOM_TUNINGS = stringPreferencesKey("custom_tunings")
        val SCALE_ROOT = intPreferencesKey("scale_root")
        val SCALE_ID = stringPreferencesKey("scale_id")
        val SCALE_FRETS = intPreferencesKey("scale_frets")
        val SCALE_LABELS = stringPreferencesKey("scale_labels")
        val LEFT_HANDED = booleanPreferencesKey("left_handed")
    }
}
