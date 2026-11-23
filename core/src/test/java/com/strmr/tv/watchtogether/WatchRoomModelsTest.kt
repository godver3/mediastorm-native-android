package com.strmr.tv.watchtogether

import com.strmr.tv.api.ApiJson
import com.strmr.tv.api.DetailsTarget
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Watch Together payloads as the server encodes them (models/watch_room.go JSON tags, Go's
 * `time.Time` as RFC 3339, `omitempty` fields left out, a nil slice as null).
 */
class WatchRoomModelsTest {
    private val roomJson = """
        {"id":"wr_7f3a","creatorProfileId":"p-host","creatorName":"Liam","title":"Dune: Part Two","mediaType":"movie",
         "itemId":"tmdb:movie:693134","posterUrl":"https://image.tmdb.org/t/p/w500/poster.jpg",
         "params":{"title":"Dune: Part Two","titleId":"tmdb:movie:693134","mediaType":"movie","year":"2024","tmdbId":"693134",
                   "imdbId":"tt15239678","tvdbId":"","backdropUrl":"https://image.tmdb.org/t/p/original/bd.jpg","description":"Paul..."},
         "status":"playing","waitingForReady":false,"position":1834.52,"duration":9960.1,"revision":12,"updatedBy":"p-guest",
         "anchorUpdatedAt":"2026-11-23T20:15:02.123456789Z","createdAt":"2026-11-23T19:58:00Z","expiresAt":"2026-11-24T19:58:00Z",
         "members":[
          {"profileId":"p-host","name":"Liam","color":"#f5b400","iconUrl":"/api/users/p-host/icon","clientId":"strmr-androidtv-1",
           "isCreator":true,"ready":true,"buffering":false,"joined":true,"present":true,"joinedAt":"2026-11-23T19:58:00Z",
           "lastSeenAt":"2026-11-23T20:15:03Z","capabilities":{"nativePlayback":true,"stateSync":true,"protocolVersion":1}},
          {"profileId":"guest:ab12","name":"Sam","isGuest":true,"isCreator":false,"ready":false,"buffering":true,"joined":true,
           "present":false,"joinedAt":"2026-11-23T20:01:00Z","lastSeenAt":"2026-11-23T20:14:30Z",
           "capabilities":{"nativePlayback":false,"stateSync":true,"protocolVersion":1}}
         ],"futureField":{"x":1}}
    """.trimIndent()

    @Test fun decodesARoom() {
        val room = ApiJson.decodeFromString<WatchRoom>(roomJson)
        assertEquals("wr_7f3a", room.id)
        assertTrue(room.isPlaying)
        assertFalse(room.ended)
        assertEquals(1834.52, room.position, 0.001)
        assertEquals(12L, room.revision)
        assertTrue(room.isHost("p-host"))
        assertFalse(room.isHost("guest:ab12"))
        val guest = room.member("guest:ab12")!!
        assertTrue(guest.isGuest && guest.buffering && !guest.present)
        assertEquals(1, room.member("p-host")!!.capabilities.protocolVersion)
        assertNull(room.endReason)
        assertEquals("2024", room.params["year"])
    }

    @Test fun decodesAnEndedRoomWithNullMembers() {
        val room = ApiJson.decodeFromString<WatchRoom>(
            """{"id":"wr_1","creatorProfileId":"p","creatorName":"A","title":"T","mediaType":"series","itemId":"tvdb:series:1",
               "params":null,"status":"ended","waitingForReady":false,"position":0,"duration":0,"revision":3,
               "anchorUpdatedAt":"2026-11-23T20:00:00Z","createdAt":"2026-11-23T20:00:00Z","expiresAt":"2026-11-24T20:00:00Z",
               "endedAt":"2026-11-23T21:00:00Z","endReason":"host_disconnected","members":null}""",
        )
        assertTrue(room.ended)
        assertTrue(room.members.isEmpty())
        assertTrue(room.params.isEmpty())
        assertEquals("The host disconnected.", room.endedMessage)
    }

    @Test fun decodesInvitationLists() {
        val invites = ApiJson.decodeFromString<List<WatchRoomAccountInvitation>>(
            """[{"id":"inv1","roomId":"wr_7f3a","inviteeUsername":"sam","status":"pending","creatorName":"Liam","title":"Dune: Part Two",
                 "mediaType":"movie","itemId":"tmdb:movie:693134","createdAt":"2026-11-23T19:58:00Z","expiresAt":"2026-11-24T19:58:00Z"},
                {"id":"inv2","roomId":"wr_x","status":"accepted","acceptedProfileId":"p2","createdAt":"2026-11-23T19:58:00Z",
                 "expiresAt":"2026-11-24T19:58:00Z","respondedAt":"2026-11-23T20:00:00Z"}]""",
        )
        assertEquals(2, invites.size)
        assertTrue(invites[0].isPending)
        assertFalse(invites[1].isPending)
        assertEquals("p2", invites[1].acceptedProfileId)
        val external = ApiJson.decodeFromString<WatchPartyInvitation>(
            """{"url":"https://ms.example/watch-party/tok123","landingUrl":"https://ms.example/watch-party","shortCode":"K7QM-2XHD","expiresAt":"2026-11-24T19:58:00Z"}""",
        )
        assertEquals("K7QM-2XHD", external.shortCode)
    }

    @Test fun createBodyHasExactlyTheServerFields() {
        val target = DetailsTarget("tvdb:series:81189", "Breaking Bad", isMovie = false, year = 2008, tvdbId = "81189",
                                   posterUrl = "https://p/poster.jpg", backdropUrl = "https://p/bd.jpg")
        val body = WatchRoomTitle.create(target, listOf("p2")).body(clientId = "strmr-androidtv-test")
        // The server decodes with DisallowUnknownFields: anything else is a 400.
        assertEquals(
            setOf("title", "mediaType", "itemId", "posterUrl", "backdropUrl", "params", "inviteeProfileIds", "clientId", "capabilities"),
            body.keys,
        )
        assertEquals("series", body["mediaType"]!!.jsonPrimitive.content)
        assertEquals("tvdb:series:81189", body["itemId"]!!.jsonPrimitive.content)
        assertEquals(listOf("p2"), (body["inviteeProfileIds"] as JsonArray).map { it.jsonPrimitive.content })
        val caps = body["capabilities"]!!.jsonObject
        assertEquals("true", caps["nativePlayback"]!!.jsonPrimitive.content)
        assertEquals("1", caps["protocolVersion"]!!.jsonPrimitive.content)
        val params = body["params"] as JsonObject
        assertEquals("Breaking Bad", params["title"]!!.jsonPrimitive.content)
    }

    @Test fun titleRoundTripsThroughRoomParams() {
        val target = DetailsTarget("tmdb:movie:693134", "Dune: Part Two", isMovie = true, year = 2024, tmdbId = "693134",
                                   imdbId = "tt15239678", backdropUrl = "https://bd", posterUrl = "https://p", overview = "Paul")
        val room = WatchRoom(id = "r", itemId = target.titleId, mediaType = "movie", params = WatchRoomTitle.params(target))
        assertEquals(target.copy(textPosterUrl = null), WatchRoomTitle.target(room))
        // An Expo room's series params.
        val series = WatchRoomTitle.target(ApiJson.decodeFromString(roomJson.replace("\"mediaType\":\"movie\"", "\"mediaType\":\"series\"")))
        assertFalse(series.isMovie)
        assertNull("an empty id is no id", series.tvdbId)
    }

    @Test fun itemIdsMatchAcrossEpisodeSuffixes() {
        assertTrue(WatchRoomTitle.matches("tvdb:series:81189", "tvdb:series:81189:S01E02"))
        assertTrue(WatchRoomTitle.matches("tvdb:series:81189", "tvdb:series:81189"))
        assertFalse(WatchRoomTitle.matches("tvdb:series:81189", "tvdb:series:1"))
    }
}
