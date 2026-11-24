package com.strmr.tv.ui.details

import org.junit.Assert.assertEquals
import org.junit.Test

/** Customize Buttons: what's in More when nothing is stored, and reading back stored choices. */
class DetailsButtonsTest {
    @Test fun manageListsAndQualityStartInMore() {
        assertEquals(setOf(DetailsAction.LISTS, DetailsAction.QUALITY), DetailsButtons.decode(null))
    }

    @Test fun storedChoicesReplaceTheDefaults() {
        assertEquals(setOf(DetailsAction.QUALITY), DetailsButtons.decode(emptySet()))
        assertEquals(setOf(DetailsAction.TRAILER, DetailsAction.WATCHED, DetailsAction.QUALITY), DetailsButtons.decode(setOf("TRAILER", "WATCHED")))
    }

    @Test fun unknownNamesAreDropped() {
        assertEquals(setOf(DetailsAction.VERSIONS, DetailsAction.QUALITY), DetailsButtons.decode(setOf("VERSIONS", "GONE")))
    }

    @Test fun qualityMovedToTheRowRoundTrips() {
        val stored = DetailsButtons.encode(setOf(DetailsAction.LISTS))
        assertEquals(setOf("LISTS", "row:QUALITY"), stored)
        assertEquals(setOf(DetailsAction.LISTS), DetailsButtons.decode(stored))
        assertEquals(setOf(DetailsAction.LISTS, DetailsAction.QUALITY), DetailsButtons.decode(DetailsButtons.encode(setOf(DetailsAction.LISTS, DetailsAction.QUALITY))))
    }
}
