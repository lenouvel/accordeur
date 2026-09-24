package com.blenouvel.accordeur.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Icônes de navigation (24 dp), teintées par `Icon`. */
object AppIcons {
    private val ink = SolidColor(Color.Black)

    private fun PathBuilder.dot(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcToRelative(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = 2 * r, dy1 = 0f)
        arcToRelative(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = -2 * r, dy1 = 0f)
        close()
    }

    /** Jauge d'accordeur : arc, aiguille, pivot. */
    val Tuner: ImageVector by lazy {
        ImageVector.Builder("Tuner", 24.dp, 24.dp, 24f, 24f)
            .path(stroke = ink, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(3.5f, 16f)
                arcTo(8.5f, 8.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 20.5f, y1 = 16f)
            }
            .path(stroke = ink, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(12f, 16f)
                lineTo(15.5f, 9f)
            }
            .path(fill = ink) { dot(12f, 16f, 2f) }
            .build()
    }

    /** Manche vu de face : sillet, frettes, cordes et deux notes. */
    val Fretboard: ImageVector by lazy {
        ImageVector.Builder("Fretboard", 24.dp, 24.dp, 24f, 24f)
            .path(stroke = ink, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round) {
                moveTo(6.5f, 3f)
                lineTo(6.5f, 21.5f)
                moveTo(17.5f, 3f)
                lineTo(17.5f, 21.5f)
            }
            .path(stroke = ink, strokeLineWidth = 2.6f, strokeLineCap = StrokeCap.Round) {
                moveTo(6.5f, 4.5f)
                lineTo(17.5f, 4.5f)
            }
            .path(stroke = ink, strokeLineWidth = 1.2f) {
                moveTo(6.5f, 10f)
                lineTo(17.5f, 10f)
                moveTo(6.5f, 15.5f)
                lineTo(17.5f, 15.5f)
                moveTo(10.2f, 4.5f)
                lineTo(10.2f, 21.5f)
                moveTo(13.8f, 4.5f)
                lineTo(13.8f, 21.5f)
            }
            .path(fill = ink) {
                dot(10.2f, 7.25f, 2.1f)
                dot(13.8f, 12.75f, 2.1f)
                dot(10.2f, 18.5f, 2.1f)
            }
            .build()
    }
}
