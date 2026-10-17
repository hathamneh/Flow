package io.github.aedev.flow.data.youtubeaccount

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class YouTubeAccountParsersTest {
    private fun parse(text: String) = Json.parseToJsonElement(text)

    @Test
    fun `accounts reads the owner and brand channels, skipping disabled ones`() {
        val root =
            parse(
                """
                {"contents":[{"accountSectionListRenderer":{"contents":[{"accountItemSectionRenderer":{"contents":[
                  {"accountItem":{"accountName":{"simpleText":"Haitham"},"accountByline":{"simpleText":"me@example.com"},
                    "channelHandle":{"simpleText":"@haitham"},"isSelected":true,
                    "accountPhoto":{"thumbnails":[{"url":"//small.jpg"},{"url":"https://big.jpg"}]},
                    "serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[{"accountStateToken":{}}]}}}},
                  {"accountItem":{"accountName":{"runs":[{"text":"Brand "},{"text":"Channel"}]},"isSelected":false,
                    "serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[{"pageIdToken":{"pageId":"12345"}}]}}}},
                  {"accountItem":{"accountName":{"simpleText":"Gone"},"isDisabled":true}}
                ]}}]}}]}
                """,
            )

        val accounts = YouTubeAccountParsers.accounts(root)

        assertThat(accounts).hasSize(2)
        assertThat(accounts[0])
            .isEqualTo(RemoteIdentity("Haitham", "me@example.com", "@haitham", "https://big.jpg", pageId = null, isSelected = true))
        assertThat(accounts[1].name).isEqualTo("Brand Channel")
        assertThat(accounts[1].pageId).isEqualTo("12345")
        assertThat(accounts[1].isSelected).isFalse()
    }

    @Test
    fun `tracking urls come from the player response`() {
        val root =
            parse(
                """
                {"playbackTracking":{
                  "videostatsPlaybackUrl":{"baseUrl":"https://s.youtube.com/api/stats/playback?docid=abc&ei=E"},
                  "videostatsWatchtimeUrl":{"baseUrl":"https://s.youtube.com/api/stats/watchtime?docid=abc&ei=E"}}}
                """,
            )

        val urls = YouTubeAccountParsers.trackingUrls(root)

        assertThat(urls?.playbackUrl).endsWith("/playback?docid=abc&ei=E")
        assertThat(urls?.watchtimeUrl).endsWith("/watchtime?docid=abc&ei=E")
    }

    @Test
    fun `tracking urls are null when the player refused the video`() {
        assertThat(YouTubeAccountParsers.trackingUrls(parse("""{"playabilityStatus":{"status":"LOGIN_REQUIRED"}}"""))).isNull()
    }

    @Test
    fun `channels reads one tab per subscribed channel and ignores other tabs`() {
        val root =
            parse(
                """
                {"contents":{"tvBrowseRenderer":{"content":{"tvSecondaryNavRenderer":{"sections":[{"tvSecondaryNavSectionRenderer":{"tabs":[
                  {"tabRenderer":{"title":"All","endpoint":{"browseEndpoint":{"browseId":"FEsubscriptions"}}}},
                  {"tabRenderer":{"title":{"simpleText":"Veritasium"},
                    "endpoint":{"browseEndpoint":{"browseId":"UCHnyfMqiRRG1u-2MsSQLbXA"}},
                    "thumbnail":{"thumbnails":[{"url":"https://v.jpg"}]}}},
                  {"tabRenderer":{"title":{"simpleText":"Veritasium again"},
                    "endpoint":{"browseEndpoint":{"browseId":"UCHnyfMqiRRG1u-2MsSQLbXA"}}}}
                ]}}]}}}}}
                """,
            )

        val channels = YouTubeAccountParsers.channels(root)

        assertThat(channels).containsExactly(RemoteChannel("UCHnyfMqiRRG1u-2MsSQLbXA", "Veritasium", "https://v.jpg"))
    }

    @Test
    fun `channels reads the round channel tiles of the TV channel list`() {
        val root =
            parse(
                """
                {
                  "contents": {
                    "tvBrowseRenderer": {
                      "content": {
                        "shelfRenderer": {
                          "content": {
                            "horizontalListRenderer": {
                              "items": [
                                {
                                  "tileRenderer": {
                                    "style": "TILE_STYLE_YTLR_ROUND",
                                    "contentType": "TILE_CONTENT_TYPE_CHANNEL",
                                    "contentId": "UCKUwNueRpPeZcV-nPglMKCQ",
                                    "header": {
                                      "tileHeaderRenderer": {
                                        "thumbnail": {
                                          "thumbnails": [
                                            {
                                              "url": "//yt3.example/88"
                                            },
                                            {
                                              "url": "//yt3.example/176"
                                            }
                                          ]
                                        }
                                      }
                                    },
                                    "metadata": {
                                      "tileMetadataRenderer": {
                                        "title": {
                                          "simpleText": "A Channel"
                                        },
                                        "lines": [
                                          {
                                            "lineRenderer": {
                                              "items": [
                                                {
                                                  "lineItemRenderer": {
                                                    "text": {
                                                      "simpleText": "1M subscribers"
                                                    }
                                                  }
                                                }
                                              ]
                                            }
                                          }
                                        ]
                                      }
                                    },
                                    "onSelectCommand": {
                                      "browseEndpoint": {
                                        "browseId": "UCKUwNueRpPeZcV-nPglMKCQ"
                                      }
                                    }
                                  }
                                }
                              ]
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """,
            )

        assertThat(YouTubeAccountParsers.channels(root))
            .containsExactly(RemoteChannel("UCKUwNueRpPeZcV-nPglMKCQ", "A Channel", "https://yt3.example/176"))
    }

    @Test
    fun `playlists reads library playlist tiles and skips the built-in lists`() {
        val root =
            parse(
                """
                {"contents":{"tvBrowseRenderer":{"content":{"tvSurfaceContentRenderer":{"content":{"gridRenderer":{"items":[
                  {"tileRenderer":{"contentType":"TILE_CONTENT_TYPE_PLAYLIST","contentId":"PLZmiPrHYOIsRkZaCb5SVAxu",
                    "header":{"tileHeaderRenderer":{"thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/pl_c/x.jpg"}]}}},
                    "metadata":{"tileMetadataRenderer":{"title":{"simpleText":"Road trip"}}},
                    "onSelectCommand":{"browseEndpoint":{"browseId":"VLPLZmiPrHYOIsRkZaCb5SVAxu"}}}},
                  {"tileRenderer":{"contentType":"TILE_CONTENT_TYPE_PLAYLIST","contentId":"WL",
                    "metadata":{"tileMetadataRenderer":{"title":{"simpleText":"Watch later"}}}}}
                ]}}}}}}}
                """,
            )

        assertThat(YouTubeAccountParsers.playlists(root))
            .containsExactly(RemotePlaylist("PLZmiPrHYOIsRkZaCb5SVAxu", "Road trip", "https://i.ytimg.com/pl_c/x.jpg"))
    }

    @Test
    fun `liked music tiles are marked as music`() {
        val root =
            parse(
                """
                {"items":[{"tileRenderer":{"contentType":"TILE_CONTENT_TYPE_VIDEO","contentId":"dQw4w9WgXcQ",
                  "metadata":{"tileMetadataRenderer":{"title":{"runs":[{"text":"A Song"}]}}},
                  "customData":{"playlistPageTileData":{"musicVideoType":"MUSIC_VIDEO_TYPE_OMV"}}}}]}
                """,
            )

        assertThat(YouTubeAccountParsers.videos(root).single().isMusic).isTrue()
    }

    @Test
    fun `history reads video tiles with title, channel, length and resume bar`() {
        val root =
            parse(
                """
                {"contents":{"tvBrowseRenderer":{"content":{"tvSurfaceContentRenderer":{"content":{"gridRenderer":{"items":[
                  {"tileRenderer":{"contentType":"TILE_CONTENT_TYPE_VIDEO","contentId":"dQw4w9WgXcQ",
                    "header":{"tileHeaderRenderer":{
                      "thumbnail":{"thumbnails":[{"url":"https://t.jpg"}]},
                      "thumbnailOverlays":[
                        {"thumbnailOverlayTimeStatusRenderer":{"text":{"simpleText":"3:33"}}},
                        {"thumbnailOverlayResumePlaybackRenderer":{"percentDurationWatched":50}}]}},
                    "metadata":{"tileMetadataRenderer":{"title":{"simpleText":"Never Gonna Give You Up"},
                      "lines":[{"lineRenderer":{"items":[{"lineItemRenderer":{"text":{"runs":[{"text":"Rick Astley",
                        "navigationEndpoint":{"browseEndpoint":{"browseId":"UCuAXFkgsw1L7xaCfnd5JJOw"}}}]}}}]}}]}}}},
                  {"tileRenderer":{"contentType":"TILE_CONTENT_TYPE_PLAYLIST","contentId":"PL123",
                    "metadata":{"tileMetadataRenderer":{"title":{"simpleText":"A playlist"}}}}}
                ]}}}}}},
                "continuationContents":{},
                "x":{"nextContinuationData":{"continuation":"NEXT"}}}
                """,
            )

        val history = YouTubeAccountParsers.history(root)

        assertThat(history).hasSize(1)
        val item = history.single()
        assertThat(item.videoId).isEqualTo("dQw4w9WgXcQ")
        assertThat(item.title).isEqualTo("Never Gonna Give You Up")
        assertThat(item.channelName).isEqualTo("Rick Astley")
        assertThat(item.channelId).isEqualTo("UCuAXFkgsw1L7xaCfnd5JJOw")
        assertThat(item.thumbnailUrl).isEqualTo("https://t.jpg")
        assertThat(item.durationMs).isEqualTo(213_000L)
        assertThat(item.percentWatched).isEqualTo(50)
        assertThat(YouTubeAccountParsers.continuation(root)).isEqualTo("NEXT")
    }

    @Test
    fun `continuation also reads the continuation command shape`() {
        val root = parse("""{"a":[{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"T2"}}}}]}""")
        assertThat(YouTubeAccountParsers.continuation(root)).isEqualTo("T2")
    }

    @Test
    fun `clock text converts to milliseconds`() {
        assertThat(YouTubeAccountParsers.parseClockMs("1:02:03")).isEqualTo(3_723_000L)
        assertThat(YouTubeAccountParsers.parseClockMs("12:34")).isEqualTo(754_000L)
        assertThat(YouTubeAccountParsers.parseClockMs("LIVE")).isNull()
    }
}
