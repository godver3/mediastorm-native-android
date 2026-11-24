package com.strmr.tv.ui.details

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.api.QualityPreset
import com.strmr.tv.api.QualityPresetChoices
import com.strmr.tv.api.qualityPresets
import com.strmr.tv.player.PlaybackCoordinator
import java.util.concurrent.ConcurrentHashMap

/**
 * Quality presets picked on details pages, per profile and title, until the app restarts, plus
 * the name of the preset that ranks each title (for the Play error when nothing matched it).
 * The server remembers a pick once a prequeue sends it; until then, and in case it still ranks
 * with the old preset, Play, the warm-up and Choose Source send the pick themselves (as the web
 * client does).
 */
object QualityPresetPicks {
    private val picked = ConcurrentHashMap<String, String>()
    private val active = ConcurrentHashMap<String, String>()
    private fun key(profile: String, titleId: String) = "$profile|$titleId"

    fun picked(titleId: String, profile: String = ApiClient.profileId): String? = picked[key(profile, titleId)]
    fun pick(titleId: String, id: String, profile: String = ApiClient.profileId) { picked[key(profile, titleId)] = id }

    /** The preset (not the simple ranking) ranking `titleId`'s releases, by name; null: none. */
    fun activeName(titleId: String, profile: String = ApiClient.profileId): String? = active[key(profile, titleId)]
    fun setActive(titleId: String, name: String?, profile: String = ApiClient.profileId) {
        if (name == null) active.remove(key(profile, titleId)) else active[key(profile, titleId)] = name
    }

    /** The Play / prequeue error when a preset ranked the title and no release passed it. */
    fun noMatchMessage(name: String) =
        "No release matched the quality preset “$name”. Try Choose Source → Other qualities, or pick another Quality."
}

/**
 * The details page's Quality action: the presets offered for the title (none hides it
 * everywhere: row, More, Customize Buttons), which one is active, and picking one.
 */
class QualityPresetState(private val target: DetailsTarget) {
    var choices by mutableStateOf<QualityPresetChoices?>(null)
        private set
    /** Bumped on a pick, so Choose Source searches again instead of reusing its results. */
    var revision by mutableStateOf(0)
        private set
    private var pickedHere by mutableStateOf(QualityPresetPicks.picked(target.titleId))

    suspend fun load() {
        choices = ApiClient.qualityPresets(target)
        publishActive()
    }

    val offered: List<QualityPreset> get() = offered(choices, target.isMovie)
    val visible: Boolean get() = offered.isNotEmpty()
    val activeId: String? get() = activeId(choices, target.isMovie, pickedHere)
    val defaultId: String? get() = choices?.let { QualityPresetChoices.idOf(it.default(target.isMovie)) }
    val activeName: String? get() = activeName(choices, target.isMovie, pickedHere)
    val label: String get() = label(activeName)
    /** The pick to send with a search (null: let the server choose). */
    val requested: String? get() = pickedHere
    /** A preset (not the simple ranking) ranks this title: Choose Source lists Other qualities. */
    val presetRanked: Boolean get() = visible && activeId != null && activeId != QualityPresetChoices.SIMPLE

    /**
     * Picks [id] for the title: the warm prequeue is cancelled and restarted with it (the server
     * remembers it for the profile, device and title; the default forgets it) and Choose Source
     * searches again.
     */
    fun pick(id: String) {
        if (id == activeId) return
        QualityPresetPicks.pick(target.titleId, id)
        pickedHere = id
        revision++
        publishActive()
        PlaybackCoordinator.rewarm(target)
    }

    private fun publishActive() =
        QualityPresetPicks.setActive(target.titleId, if (presetRanked) activeName else null)

    companion object {
        fun offered(c: QualityPresetChoices?, isMovie: Boolean) = c?.offeredFor(isMovie).orEmpty()

        fun activeId(c: QualityPresetChoices?, isMovie: Boolean, picked: String?): String? =
            picked ?: c?.let { QualityPresetChoices.idOf(it.current(isMovie)) }

        fun activeName(c: QualityPresetChoices?, isMovie: Boolean, picked: String?): String? {
            val id = activeId(c, isMovie, picked) ?: return null
            return offered(c, isMovie).firstOrNull { it.id == id }?.name
                ?: c?.current(isMovie)?.name?.ifEmpty { null }
        }

        fun label(name: String?) = if (name.isNullOrEmpty()) "Quality" else "Quality: $name"
    }
}
