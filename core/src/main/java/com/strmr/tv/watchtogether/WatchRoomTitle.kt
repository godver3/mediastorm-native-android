package com.strmr.tv.watchtogether

import com.strmr.tv.api.DetailsTarget

/**
 * A room's title both ways: the details-route params a room is created with (the Expo app's
 * keys, so every client can reopen it) and the [DetailsTarget] read back from them.
 */
object WatchRoomTitle {
    /** `mediaType` as rooms carry it: `movie` or `series`. */
    fun mediaType(target: DetailsTarget) = if (target.isMovie) "movie" else "series"

    fun params(target: DetailsTarget): Map<String, String> = buildMap {
        put("title", target.name)
        put("titleId", target.titleId)
        put("mediaType", mediaType(target))
        put("description", target.overview.orEmpty())
        val backdrop = target.backdropUrl.orEmpty()
        put("headerImage", backdrop.ifEmpty { target.posterUrl.orEmpty() })
        put("posterUrl", target.posterUrl.orEmpty())
        put("logoUrl", target.logoUrl.orEmpty())
        put("backdropUrl", backdrop)
        put("year", target.year?.toString().orEmpty())
        put("tmdbId", target.tmdbId.orEmpty())
        put("imdbId", target.imdbId.orEmpty())
        put("tvdbId", target.tvdbId.orEmpty())
    }

    fun create(target: DetailsTarget, inviteeProfileIds: List<String>, episode: WatchRoomEpisode? = null) = WatchRoomCreate(
        title = target.name,
        mediaType = mediaType(target),
        itemId = target.titleId,
        posterUrl = target.posterUrl,
        backdropUrl = target.backdropUrl ?: target.posterUrl,
        params = params(target),
        inviteeProfileIds = inviteeProfileIds,
        episode = episode?.takeIf { !target.isMovie },
    )

    /** A playing episode as the room carries it. */
    fun episode(info: com.strmr.tv.player.PlaybackRequest.EpisodeInfo?): WatchRoomEpisode? =
        info?.let { WatchRoomEpisode(it.season, it.episode, it.itemId, it.name) }

    /** The room's title as a details target; the room's own fields fill in what the params lack. */
    fun target(room: WatchRoom): DetailsTarget {
        val p = room.params
        fun v(key: String) = p[key]?.trim()?.ifEmpty { null }
        val mediaType = v("mediaType") ?: room.mediaType
        return DetailsTarget(
            titleId = v("titleId") ?: room.itemId,
            name = v("title") ?: room.title,
            isMovie = mediaType != "series" && mediaType != "show" && mediaType != "tv",
            year = v("year")?.toIntOrNull(),
            tmdbId = v("tmdbId"),
            imdbId = v("imdbId"),
            tvdbId = v("tvdbId"),
            posterUrl = v("posterUrl") ?: room.posterUrl,
            backdropUrl = v("backdropUrl") ?: v("headerImage") ?: room.backdropUrl,
            logoUrl = v("logoUrl"),
            overview = v("description"),
        )
    }

    /**
     * Whether [playingTitleId] is the room's title. Episode ids carry an `:SxxEyy` tail the
     * room's series id doesn't (the Expo `normalizeMediaId`).
     */
    fun matches(roomItemId: String, playingTitleId: String): Boolean {
        val a = normalize(roomItemId)
        val b = normalize(playingTitleId)
        return a.isEmpty() || b.isEmpty() || a == b
    }

    fun normalize(id: String) = id.replace(Regex(":S\\d+E\\d+$", RegexOption.IGNORE_CASE), "").trim()
}
