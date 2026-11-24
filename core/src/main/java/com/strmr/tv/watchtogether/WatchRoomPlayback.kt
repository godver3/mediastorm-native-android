package com.strmr.tv.watchtogether

import com.strmr.tv.api.Episode
import com.strmr.tv.api.StartPoint
import com.strmr.tv.ui.details.DetailsModel

/**
 * What a room plays, resolved as Play on its details page would: a movie, a series' up-next
 * episode, or (when the room names one) the host's episode. Shared by the lobby's start and a
 * guest's switch to the host's episode.
 */
object WatchRoomPlayback {
    /** The loaded title, the [episode] to play (null for a movie) and this profile's own resume point in it. */
    class Prepared(val model: DetailsModel, val episode: Episode?, val ownStart: StartPoint?) {
        val ownResume: Double get() = (ownStart as? StartPoint.Seconds)?.value ?: 0.0

        /** The playback request, started at [start]. */
        fun request(start: StartPoint?) = model.request(episode, start)
    }

    suspend fun prepare(room: WatchRoom, episode: WatchRoomEpisode? = room.episode): Prepared {
        val model = DetailsModel(WatchRoomTitle.target(room))
        model.load()
        model.loadLibrary()
        val primary = model.primary
        if (episode != null && !model.target.isMovie) {
            val found = model.seasons.flatMap { it.episodes }
                .firstOrNull { it.seasonNumber == episode.seasonNumber && it.episodeNumber == episode.episodeNumber }
                ?: throw IllegalStateException("${episode.code} isn't available for this title.")
            return Prepared(model, found, model.resumePoint(found))
        }
        primary ?: throw IllegalStateException(model.loadError ?: "Nothing to play for this title.")
        return Prepared(model, primary.episode, primary.start)
    }
}
