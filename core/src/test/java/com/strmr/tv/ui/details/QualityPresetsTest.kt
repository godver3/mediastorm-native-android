package com.strmr.tv.ui.details

import com.strmr.tv.api.ApiJson
import com.strmr.tv.api.QualityPresetChoices
import com.strmr.tv.api.SourceResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GET /api/quality-presets decoding, the Quality action's visibility and label, Choose Source sections. */
class QualityPresetsTest {
    private val body = """
        {"allowRequestOverride":false,"presets":[],
         "offered":[{"id":"simple","name":"Simple","app":"","mediaType":""},
                    {"id":"uhd","name":"UHD Remux","app":"radarr","mediaType":"movie"},
                    {"id":"hd-tv","name":"HD TV","app":"sonarr","mediaType":"series"}],
         "movie":{"presetId":"uhd","name":"UHD Remux","source":"title"},
         "series":{"presetId":"","name":"","source":""},
         "movieDefault":{"presetId":"","name":"","source":"server"},
         "seriesDefault":{"presetId":"hd-tv","name":"HD TV","source":"server"},
         "anime":false}
    """
    private val choices = ApiJson.decodeFromString<QualityPresetChoices>(body)

    @Test fun decodesAndFiltersByMediaType() {
        assertEquals(listOf("simple", "uhd"), choices.offeredFor(isMovie = true).map { it.id })
        assertEquals(listOf("simple", "hd-tv"), choices.offeredFor(isMovie = false).map { it.id })
        assertEquals("simple", QualityPresetChoices.idOf(choices.movieDefault))
        assertEquals("hd-tv", QualityPresetChoices.idOf(choices.seriesDefault))
    }

    @Test fun activeAndLabel() {
        assertEquals("uhd", QualityPresetState.activeId(choices, true, null))
        assertEquals("Quality: UHD Remux", QualityPresetState.label(QualityPresetState.activeName(choices, true, null)))
        // A pick made here wins over the server's current one.
        assertEquals("Quality: Simple", QualityPresetState.label(QualityPresetState.activeName(choices, true, "simple")))
        assertEquals("simple", QualityPresetState.activeId(choices, false, null))
    }

    @Test fun hiddenWithoutOfferedPresets() {
        assertTrue(QualityPresetState.offered(null, true).isEmpty())
        val none = ApiJson.decodeFromString<QualityPresetChoices>("""{"offered":[{"id":"x","name":"X","mediaType":"series"}]}""")
        assertTrue(QualityPresetState.offered(none, true).isEmpty())
        assertEquals("Quality", QualityPresetState.label(null))
    }

    @Test fun sectionsKeepPresetRejectsAndDropOtherFiltered() {
        val list = Json.parseToJsonElement("""[
          {"title":"A","attributes":{"arrQuality":"WEB-2160p","customFormatScore":"150","qualityPreset":"UHD Remux"},"filterStatus":"passed"},
          {"title":"B","attributes":{"arrQuality":"WEBDL-1080p","customFormatScore":"-20"},"filterStatus":"filtered","filterReason":"quality preset: quality not wanted"},
          {"title":"C","filterStatus":"filtered","filterReason":"size above limit"}
        ]""") as JsonArray
        val results = list.mapIndexedNotNull { i, e -> SourceResult.from(e as JsonObject, i) }
        val s = SourceSections.of(results)
        assertEquals(listOf("A"), s.main.map { it.title })
        assertEquals(listOf("B"), s.otherQualities.map { it.title })
        assertEquals("UHD Remux", s.rankedBy)
        assertEquals(listOf("WEB-2160p", "CF +150"), SourceSections.tags(results[0]))
        assertEquals(listOf("WEBDL-1080p", "CF -20"), SourceSections.tags(results[1]))
        assertNull(results[0].filterReason)
    }
}
