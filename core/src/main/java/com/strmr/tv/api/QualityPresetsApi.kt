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
    /** Titles a local library has play from there unless another preset is picked (server setting). */
    val preferLibraryCopies: Boolean? = null,
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

        /** "Library copy": the title plays from a local library (offered only when one has it). */
        const val LIBRARY = "local"

        /** The offered preset a selection names ("simple" for the simple ranking). */
        fun idOf(s: QualityPresetSelection?): String = s?.presetId?.ifEmpty { null } ?: SIMPLE
    }
}

/** Which library copies Play takes. */
enum class LibraryCopyRule { ALL, REMOTE, NONE }

/**
 * Which library copies Play takes, as the server decides for every other client: all of them when
 * "Library copy" is picked or is the title's choice (and without presets, as before); none when
 * another preset is picked for the title; with the server setting off only Plex and Jellyfin
 * copies, which the setting does not cover.
 */
fun libraryCopyRule(choices: QualityPresetChoices?, isMovie: Boolean, picked: String?): LibraryCopyRule {
    val c = choices ?: return LibraryCopyRule.ALL
    if (c.preferLibraryCopies == false) return LibraryCopyRule.REMOTE
    // Without a local copy the choice is not offered: picks are about ranking.
    if (c.offeredFor(isMovie).none { it.id == QualityPresetChoices.LIBRARY }) return LibraryCopyRule.ALL
    if (picked != null) return if (picked == QualityPresetChoices.LIBRARY) LibraryCopyRule.ALL else LibraryCopyRule.NONE
    return if (c.current(isMovie).presetId == QualityPresetChoices.LIBRARY) LibraryCopyRule.ALL else LibraryCopyRule.NONE
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
    // Kept without offered presets too when it switches library copies off (libraryCopyRule).
}.getOrNull()?.takeIf { it.offeredFor(t.isMovie).isNotEmpty() || it.preferLibraryCopies == false }
