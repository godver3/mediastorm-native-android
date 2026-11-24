package com.strmr.tv.ui.details

import com.strmr.tv.api.SourceResult

/**
 * Choose Source's results split for a quality preset: the ones the preset passed, and the ones
 * it rejected ("Other qualities", still playable). Results filtered for any other reason are
 * dropped, as without `includeFiltered`.
 */
data class SourceSections(val main: List<SourceResult>, val otherQualities: List<SourceResult>) {
    /** "Ranked by: <preset>" when a preset ranked the results. */
    val rankedBy: String? get() = (main + otherQualities).firstNotNullOfOrNull { it.rankedBy }

    companion object {
        const val PRESET_PREFIX = "quality preset:"

        fun of(results: List<SourceResult>): SourceSections {
            val main = results.filter { it.filterReason == null }
            val other = results.filter { it.filterReason?.startsWith(PRESET_PREFIX) == true }
            return SourceSections(main, other)
        }

        /** The tags a result shows: its Sonarr/Radarr quality and custom-format score. */
        fun tags(r: SourceResult): List<String> = listOfNotNull(
            r.arrQuality,
            r.customFormatScore?.let { s -> if (s.startsWith("-") || s == "0") "CF $s" else "CF +$s" },
        )
    }
}
