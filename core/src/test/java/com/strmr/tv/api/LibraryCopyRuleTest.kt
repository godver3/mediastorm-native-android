package com.strmr.tv.api

import com.strmr.tv.library.LibraryVersion
import com.strmr.tv.library.LibraryVersions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryCopyRuleTest {
    private fun choices(current: String, prefer: Boolean? = true) = QualityPresetChoices(
        offered = listOf(QualityPreset(QualityPresetChoices.LIBRARY, "Library copy", "series"), QualityPreset("simple", "Simple ranking")),
        series = QualityPresetSelection(current, ""),
        seriesDefault = QualityPresetSelection(QualityPresetChoices.LIBRARY, "Library copy"),
        preferLibraryCopies = prefer,
    )

    @Test fun libraryCopyWhenAutoUsesItStreamAfterAnotherPick() {
        assertEquals(LibraryCopyRule.ALL, libraryCopyRule(choices(QualityPresetChoices.LIBRARY), isMovie = false, picked = null))
        assertEquals(LibraryCopyRule.NONE, libraryCopyRule(choices("simple"), isMovie = false, picked = null))
        assertEquals(LibraryCopyRule.NONE, libraryCopyRule(choices(QualityPresetChoices.LIBRARY), isMovie = false, picked = "simple"))
        assertEquals(LibraryCopyRule.ALL, libraryCopyRule(choices("simple"), isMovie = false, picked = QualityPresetChoices.LIBRARY))
    }

    @Test fun serverSettingOffKeepsPlexAndJellyfinWithoutPresetsAll() {
        assertEquals(LibraryCopyRule.REMOTE, libraryCopyRule(choices(QualityPresetChoices.LIBRARY, prefer = false), isMovie = false, picked = "simple"))
        assertEquals(LibraryCopyRule.ALL, libraryCopyRule(null, isMovie = false, picked = null))
        val noOffer = choices("p1").copy(offered = emptyList())
        assertEquals(LibraryCopyRule.ALL, libraryCopyRule(noOffer, isMovie = false, picked = "simple"))
    }

    @Test fun allowedByKeepsOnlyWhatTheRuleLets() {
        val local = LibraryVersion(LibraryItem(id = "l"), "Movies")
        val plex = LibraryVersion(LibraryItem(id = "p", sourceType = "plex"), "Plex Movies")
        val versions = LibraryVersions(listOf(local, plex), emptyMap())
        assertEquals(versions, versions.allowedBy(LibraryCopyRule.ALL))
        assertEquals(listOf(plex), versions.allowedBy(LibraryCopyRule.REMOTE)?.movie)
        assertNull(versions.allowedBy(LibraryCopyRule.NONE))
        assertNull(LibraryVersions(listOf(local), emptyMap()).allowedBy(LibraryCopyRule.REMOTE))
    }
}
