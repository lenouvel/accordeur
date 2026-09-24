package com.blenouvel.accordeur.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.blenouvel.accordeur.R
import java.io.File
import java.util.Locale

/**
 * Ouvre le menu de partage d'Android pour l'archive [zip] : Proton Drive (« Importer dans Proton
 * Drive »), Google Drive, e-mail… — l'application choisie lit le fichier via le FileProvider.
 */
fun shareBankArchive(context: Context, zip: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fichiers", zip)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, zip.name)
        clipData = ClipData.newRawUri(zip.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, context.getString(R.string.bank_share_title))
    if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
        // Aucune application de partage : rien à faire.
    }
}

/** Taille lisible : « 38,2 Mo », « 850 ko ». */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.getDefault(), "%.2f Go", bytes / 1e9)
    bytes >= 1_000_000 -> String.format(Locale.getDefault(), "%.1f Mo", bytes / 1e6)
    else -> String.format(Locale.getDefault(), "%d ko", (bytes + 999) / 1000)
}

/** Durée lisible : « 3 min 25 s », « 42 s ». */
fun formatDuration(seconds: Double): String {
    val total = seconds.toInt()
    return if (total >= 60) "${total / 60} min ${total % 60} s" else "$total s"
}

/** Fréquence d'échantillonnage : « 48 kHz », « 44,1 kHz ». */
fun formatSampleRate(rate: Int): String =
    if (rate % 1000 == 0) "${rate / 1000} kHz" else String.format(Locale.getDefault(), "%.1f kHz", rate / 1000.0)
