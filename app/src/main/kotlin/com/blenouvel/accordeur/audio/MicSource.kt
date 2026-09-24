package com.blenouvel.accordeur.audio

/**
 * Source micro. AUTO : la moins traitée disponible (UNPROCESSED si le téléphone l'annonce, puis
 * VOICE_PERFORMANCE — chemin « musique en direct » sans traitement ni couplage avec la sortie —,
 * puis VOICE_RECOGNITION, puis MIC). Les autres valeurs forcent une source, pour comparer.
 */
enum class MicSource { AUTO, UNPROCESSED, VOICE_PERFORMANCE, VOICE_RECOGNITION, CAMCORDER, MIC }
