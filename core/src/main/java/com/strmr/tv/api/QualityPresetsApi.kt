package com.strmr.tv.api

import com.strmr.tv.app.Config
import kotlinx.serialization.Serializable

// Quality presets (servers with Sonarr/Radarr-style presets): the presets a version picker
// offers for a title, and which one Play would use. Shapes match the backend's
// handlers/quality_presets_selection.go (GET /api/quality-presets, clientPresetList); the web
// client's api/qualityPresets.ts is the reference.

@Serializable
data class QualityPreset(
    val id: String,
    val name: String,
    /** "movie", "series", or empty for the simple ranking, which fits both. */
    val mediaType: String? = null,
)

@Serializable
data class QualityPresetSelection(
    /** Empty when the simple ranking applies. */
    val presetId: String? = null,
    val name: String? = null,
    /** Where it comes from (title, device, profile, server, …). */
    val source: String? = null,
)

@Serializable
data class QualityPresetChoices(
    val offered: List<QualityPreset> = emptyList(),
    /** What Play uses now for the title on this device (its remembered preset, else the default). */
    val movie: QualityPresetSelection = QualityPresetSelection(),
    val series: QualityPresetSelection = QualityPresetSelection(),
    /** What it would use without a remembered preset: picking it clears the title's memory. */
    val movieDefault: QualityPresetSelection = QualityPresetSelection(),
    val seriesDefault: QualityPresetSelection = QualityPresetSelection(),
) {
    /** The presets offered for a movie or a series, in the server's order. */
    fun offeredFor(isMovie: Boolean): List<QualityPreset> {
        val type = if (isMovie) "movie" else "series"
        return offered.filter { it.mediaType.isNullOrEmpty() || it.mediaType == type }
    }

    fun current(isMovie: Boolean) = if (isMovie) movie else series
    fun default(isMovie: Boolean) = if (isMovie) movieDefault else seriesDefault

    companion object {
        /** The ID the server uses for its simple ranking (no preset). */
        const val SIMPLE = "simple"

        /** The offered preset a selection names ("simple" for the simple ranking). */
        fun idOf(s: QualityPresetSelection?): String = s?.presetId?.ifEmpty { null } ?: SIMPLE
    }
}

/**
 * The quality presets offered for a title on this profile and device, or null when the server
 * has none to offer (no presets, or a server without them: errors count as none).
 */
suspend fun ApiClient.qualityPresets(t: DetailsTarget): QualityPresetChoices? = runCatching {
    val q = buildMap {
        put("userId", profileId)
        put("clientId", Config.clientId)
        put("mediaType", if (t.isMovie) "movie" else "series")
        put("titleId", t.titleId)
        put("name", t.name)
        t.imdbId?.let { put("imdbId", it) }
        t.tmdbId?.let { put("tmdbId", it) }
        t.tvdbId?.let { put("tvdbId", it) }
        t.year?.let { put("year", it.toString()) }
    }
    send<QualityPresetChoices>("GET", "/api/quality-presets", q)
}.getOrNull()?.takeIf { it.offeredFor(t.isMovie).isNotEmpty() }
