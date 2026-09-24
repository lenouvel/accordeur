package com.blenouvel.accordeur.data

import android.content.Context
import com.blenouvel.accordeur.audio.SoundRecorder
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Banque de sons de test sur le téléphone (dossier privé de l'application, exclu des sauvegardes) :
 * bilan, export en une archive zip à partager, suppression. Les sons sont écrits par [SoundRecorder].
 */
class SoundBank(context: Context) {
    val directory = File(context.applicationContext.filesDir, DIRECTORY)
    private val exportDirectory = File(context.applicationContext.cacheDir, EXPORT_DIRECTORY)

    data class Stats(val sounds: Int, val bytes: Long, val seconds: Double)

    /** Sons terminés (fiche écrite), du plus ancien au plus récent. */
    private fun sounds(): List<File> =
        directory.listFiles { f -> f.name.endsWith(".wav") && sheetOf(f).exists() }?.sortedBy { it.name } ?: emptyList()

    private fun sheetOf(wav: File) = File(wav.parentFile, wav.name.removeSuffix(".wav") + ".txt")

    fun stats(): Stats {
        // Fiche sans son (son supprimé pendant son écriture) : on la retire.
        directory.listFiles { f -> f.name.endsWith(".txt") }?.forEach { sheet ->
            if (!File(directory, sheet.name.removeSuffix(".txt") + ".wav").exists()) sheet.delete()
        }
        var bytes = 0L
        var seconds = 0.0
        val sounds = sounds()
        for (wav in sounds) {
            bytes += wav.length() + sheetOf(wav).length()
            seconds += duration(wav)
        }
        return Stats(sounds.size, bytes, seconds)
    }

    /** Durée d'un son d'après son en-tête (fréquence, taille d'échantillon) et la taille du fichier. */
    private fun duration(wav: File): Double = try {
        RandomAccessFile(wav, "r").use { file ->
            val header = ByteArray(SoundRecorder.PCM_HEADER)
            file.readFully(header)
            val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val float = b.getShort(20).toInt() == 3
            val rate = b.getInt(24)
            val headerSize = if (float) SoundRecorder.FLOAT_HEADER else SoundRecorder.PCM_HEADER
            val bytesPerSample = if (float) 4 else 2
            if (rate > 0) (wav.length() - headerSize).coerceAtLeast(0L).toDouble() / bytesPerSample / rate else 0.0
        }
    } catch (_: Exception) {
        0.0
    }

    /**
     * Archive zip de toute la banque (sons, fiches et mode d'emploi), préparée dans le cache pour
     * être partagée ; les exports précédents sont effacés.
     */
    fun exportZip(): File {
        exportDirectory.mkdirs()
        exportDirectory.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
        val zip = File(exportDirectory, "accordeur-banque-$stamp.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zip), 1 shl 16)).use { out ->
            out.setLevel(Deflater.BEST_SPEED)
            out.putNextEntry(ZipEntry("$DIRECTORY/LISEZMOI.txt"))
            out.write(README.toByteArray(Charsets.UTF_8))
            out.closeEntry()
            for (wav in sounds()) {
                for (file in listOf(wav, sheetOf(wav))) {
                    out.putNextEntry(ZipEntry("$DIRECTORY/${file.name}"))
                    file.inputStream().use { it.copyTo(out, 1 shl 16) }
                    out.closeEntry()
                }
            }
        }
        return zip
    }

    /** Supprime tous les sons (et les archives d'export). */
    fun clear() {
        directory.listFiles()?.forEach { it.delete() }
        exportDirectory.listFiles()?.forEach { it.delete() }
    }

    companion object {
        const val DIRECTORY = SoundRecorder.DIRECTORY
        private const val EXPORT_DIRECTORY = "export"

        private val README = """
            Banque de sons de test — Accordeur
            ==================================

            Chaque son joué pendant l'écoute (quand l'option est activée) donne deux fichiers :

            - AAAAMMJJ-hhmmss-mmm_<mode>_<accordage>.wav : le signal brut du micro, tel que le
              téléphone l'a livré, avant tout filtrage. Mono, sans perte : float 32 bits, ou PCM
              16 bits si le téléphone ne fournit que du 16 bits. 1 s avant le son, 1 s après.
            - le .txt du même nom : téléphone, source micro, réglages (mode, accordage, La de
              référence, corde verrouillée), puis ce que l'accordeur a affiché à chaque trame
              (~43 ms) : niveau, son détecté, fréquence, valeur maintenue, tableau poly.

            Rejeu dans l'accordeur sur ordinateur (non-régression) : décompresser le dossier
            « banque » à la racine du projet sous le nom « testbank », puis lancer
            ./gradlew test --tests '*BankReplayTest*'
            (ou indiquer le dossier par la variable d'environnement ACCORDEUR_BANQUE).
            Le rapport compare, son par son, l'affichage de la version testée à celui enregistré.
        """.trimIndent() + "\n"
    }
}
