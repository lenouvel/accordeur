package com.blenouvel.accordeur

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Lecture des sons et des fiches de la banque (tests JVM, sans Android). */
object BankFiles {
    class Sound(val samples: FloatArray, val sampleRate: Int, val float: Boolean)

    /** Une trame de la fiche : ce que l'accordeur affichait. */
    class Frame(val time: Double, val levelDb: Double, val signal: Boolean, val frequency: Double, val holding: Boolean, val poly: String)

    class Sheet(val keys: Map<String, String>, val frames: List<Frame>) {
        operator fun get(key: String): String = keys[key] ?: error("clé absente : $key")
    }

    /** WAV mono PCM 16 bits ou float 32 bits ; tolère un en-tête non finalisé (tailles nulles). */
    fun readWav(file: File): Sound {
        val bytes = file.readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "pas un WAV : $file" }
        var offset = 12
        var tag = 0
        var channels = 0
        var rate = 0
        var bits = 0
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = b.getInt(offset + 4)
            val body = offset + 8
            when (id) {
                "fmt " -> {
                    tag = b.getShort(body).toInt()
                    channels = b.getShort(body + 2).toInt()
                    rate = b.getInt(body + 4)
                    bits = b.getShort(body + 14).toInt()
                }
                "data" -> {
                    require(channels == 1) { "son non mono : $file" }
                    val length = if (size <= 0 || body + size > bytes.size) bytes.size - body else size
                    val float = tag == 3 && bits == 32
                    require(float || (tag == 1 && bits == 16)) { "format non géré ($tag, $bits bits) : $file" }
                    val n = length / (bits / 8)
                    val samples = FloatArray(n) { i ->
                        if (float) b.getFloat(body + 4 * i) else b.getShort(body + 2 * i) / 32768f
                    }
                    return Sound(samples, rate, float)
                }
            }
            offset = body + size + (size and 1)
        }
        error("pas de données : $file")
    }

    fun readSheet(file: File): Sheet {
        val keys = LinkedHashMap<String, String>()
        val frames = ArrayList<Frame>()
        var inFrames = false
        var header = true
        for (line in file.readLines()) {
            if (line.startsWith("#") || line.isBlank()) continue
            if (line == "[trames]") {
                inFrames = true
                continue
            }
            if (!inFrames) {
                val eq = line.indexOf('=')
                if (eq > 0) keys[line.substring(0, eq)] = line.substring(eq + 1)
                continue
            }
            if (header) {
                header = false // ligne des intitulés de colonnes
                continue
            }
            val c = line.split('\t')
            frames += Frame(
                time = c[0].toDouble(),
                levelDb = c[1].toDouble(),
                signal = c[2] == "1",
                frequency = if (c[3] == "-") Double.NaN else c[3].toDouble(),
                holding = c[4] == "1",
                poly = c.getOrElse(6) { "" },
            )
        }
        return Sheet(keys, frames)
    }

    /** Paires (son, fiche) d'un dossier de banque, dans l'ordre chronologique. */
    fun sounds(directory: File): List<Pair<File, File>> =
        directory.listFiles { f -> f.name.endsWith(".wav") }
            ?.sortedBy { it.name }
            ?.mapNotNull { wav -> File(directory, wav.name.removeSuffix(".wav") + ".txt").takeIf { it.exists() }?.let { wav to it } }
            ?: emptyList()
}
