# Two-way YouTube sync: research findings

This is the research behind [PLAN.md](PLAN.md), from the architecture review done on 2026-10-17.

## Sources

- **Flow branch** `feature/google-account-sync`. `$P` = `app/src/main/java/io/github/aedev/flow/`.
- **SmartTube MediaServiceCore** (`youtubeapi/src/main/java/com/liskovsoft/youtubeapi/`, HEAD 7c2451ea):
  - `actions/ActionsApi.java`
  - `PostDataHelper.createQueryTV`
  - the playlist and history helpers
- **Other libraries:** YouTube.js (ytjs.dev), ytmusicapi, yt-dlp.
- **GitHub issues:**
  - SmartTube: #4329, #5047, #6106, #4809, #4287, #6237; commit 9c9aeba0
  - yt-dlp: #11462, PR #11558
  - ReVanced_Extended: #3235

## 1. What the TV OAuth token can do

The token is from the device-code flow, with the scope `http://gdata.youtube.com https://www.googleapis.com/auth/youtube-paid-content`. It's valid only with TVHTML5 InnerTube contexts.

- **Works with a TVHTML5 context + Bearer:**
  - subscribe and unsubscribe
  - like, dislike, remove
  - Watch Later and playlist add/remove; rename
  - save and unsave someone else's playlist
  - remove from history, using feedback tokens
  - pause, resume and clear history
  - notification bell (with caveats)
  - "watched", via a full-length stats ping

  SmartTube does all of these with exactly Flow's token and context:
  - `PostDataHelper.createQueryTV`
  - Bearer added by an OkHttp interceptor
  - no `X-YouTube-Client-Name` header on writes
  - `X-Goog-Pageid` for brand accounts
- **Dead under TV auth since Nov 2024/Jan 2025:** `playlist/create` and `playlist/delete`.
  - SmartTube commit 9c9aeba0 moved them to a WEB body.
  - The maintainer then closed SmartTube#4329: "TV's api, other apis … available only for the anonymous usage".
  - SmartTube now silently creates local-only playlists.
- **Non-TV contexts are rejected:** any non-TV context (WEB, ANDROID, WEB_REMIX) returns HTTP 400 for this token (yt-dlp#11462, PR #11558; ytjs.dev/guide/authentication). So **YouTube Music library writes are out**, with two exceptions:
  - `like/like` on a music video id lands in the account's likes, and so in "Liked music" (VLLM).
  - Following an artist is the same as subscribing.
- **Not an API at all:**
  - "Mark as watched": every client fakes it with `api/stats/playback|watchtime` and `st=et=cmt=len`.
  - "Mark unwatched": the nearest thing is "remove from history".
  - "Not interested" on items Flow ranked itself: these tokens only exist on items in a signed-in YouTube feed.
- **Data API v3 is not an option:**
  - `http://gdata.youtube.com` is the legacy v2 scope. v3 write methods require `auth/youtube[.force-ssl]`.
  - Each write costs 50 units, against a 10,000/day quota that belongs to Google's own TV project.
  - Developer policy III.D.1.b forbids using credentials not assigned to you.
  - v3 has had no history read or write since 2016.
- **Comments: out of scope.** Flow only reads them (`$P/data/comments/CommentsPager.kt`).
- **Push: none.** PubSubHubbub covers uploads only; the official app uses FCM. Propagation is "write to YouTube; other installs see it on their next foreground refresh".

## 2. Capability matrix (TV OAuth token, TVHTML5 context)

| Action | Endpoint / body | Confidence | Notes |
|---|---|---|---|
| Subscribe / unsubscribe | `subscription/subscribe\|unsubscribe` `{"channelIds":[id],"params":""}` | High | SmartTube `actions/ActionsApi.java`. One open report of it not reflecting on web (#5047), so verify. |
| Like / dislike / remove | `like/like\|dislike\|removelike\|removedislike` `{"target":{"videoId":id}}` | High | Flow already vendors `LikeBody` (`$P/innertube/models/body/LikeBody.kt`). |
| Watch Later add/remove | `browse/edit_playlist` playlistId `WL`, `ACTION_ADD_VIDEO` / `ACTION_REMOVE_VIDEO_BY_VIDEO_ID` | High | `EditPlaylistBody` is already vendored. |
| Playlist add/remove item | same, with a real playlist id | High | Add can create duplicates: pass `dedupeOption:"DEDUPE_OPTION_SKIP"` (YouTube.js). |
| Rename | `ACTION_SET_PLAYLIST_NAME` | High | Description and privacy actions exist (ytmusicapi) but are untested on TV. |
| Reorder item | `ACTION_MOVE_VIDEO_AFTER` + `setVideoId` | Low–medium | SmartTube only uses the whole-list sort mode `ACTION_SET_PLAYLIST_VIDEO_ORDER`. `setVideoId` must be read from a `VL<id>` browse. |
| Save / unsave someone else's playlist | `like/like\|removelike` `{"target":{"playlistId":id}}` | High | |
| Create / delete own playlist | `playlist/create\|delete` | **Low / broken** | Probe it. Plan for a local-only fallback with a badge. |
| Remove from history | `feedback` `{"feedbackTokens":[t]}`, with the token from the FEhistory tile menu | High | The token must be fetched from the signed-in history feed. |
| Clear / pause / resume history | `history/clear_watch_history`, `pause_watch_history`, `resume_watch_history`, body `"nop":"false"` | Medium | SmartTube only; no `account/set_setting` evidence. |
| Mark watched | GET `videostatsPlaybackUrl`, then `videostatsWatchtimeUrl` with `cmt=st=et=len` | Medium | Flow's `YouTubeWatchReporter` already has the plumbing. SmartTube #4809 shows this path is flaky. Flow omits `final=1`. |
| Mark unwatched | none; use "remove from history" | Partial | Removes the entry and the resume point. |
| Bell | `notification/modify_channel_preference` `{"params":p}`, with `p` from `/next` `subscribeButtonRenderer.notificationPreferenceButton…states[].…params` | Medium | Needs a `/next` call per change. SmartTube re-subscribes first. |
| Not interested / don't recommend | `feedback` with tile tokens | Low for Flow | Flow's home feed is its own engine, so there are no tokens. Keep it local. |
| YT Music likes | `like/like` on the track id | Medium–high | Lands in LM; verify. |
| YT Music library (albums/artists) | WEB_REMIX `feedback`/`like` | **No** | The TV token is rejected by WEB_REMIX. |
| Comments | — | Out of scope | |

Other TV browse IDs this branch already verified (the read side):

| Data | Browse ID / result |
|---|---|
| Subscribed channels | `FEchannels` (tiles with `TILE_CONTENT_TYPE_CHANNEL`) |
| Uploads feed (not a channel list) | `FEsubscriptions` |
| History | `FEhistory` |
| Playlists | `FEplaylist_aggregation` (`TILE_CONTENT_TYPE_PLAYLIST`) |
| Liked videos | `VLLL` |
| Liked music | `VLLM` (tiles carry `musicVideoType`) |
| Watch Later | `VLWL` |
| YouTube Music history | `FEmusic_history`: HTTP 400 on the TV client |

## 3. Options compared

| Option | UX / offline | Runtime cost | Effort | Risk | Verdict |
|---|---|---|---|---|---|
| (a) Local-first + persisted coalescing outbox, drained by WorkManager | Instant; works offline and replays later | One call per net user action; coalescing drops like→unlike pairs | ~8–10 person-days (store, drain, API, hooks, UI status) | Echo loops if import paths also mirror; state is stale until the next pull | **Chosen** |
| (b) Direct online call from repositories, queueing only on failure | Instant if optimistic; otherwise spinners | Same calls, but two code paths | ~6 pd | Two paths to test; failure mid-flight is the common case on mobile | Use only for `playlist/create`, which needs the returned id |
| (c) YouTube primary, Room as a read-through cache | Every screen open costs a signed-in browse; offline shows stale or empty data | 5–20× today's network use; violates "no sync on every change" | ~12 pd | Endpoint churn breaks the whole app, not one feature; breaks the guest/profile model | Rejected |
| (d) Flow's CRDT device sync for Flow↔Flow, YouTube for the rest | Only helps paired LAN peers; pairing is manual and one-shot, with no background sync | Nothing extra | 0 for transport, but two sources of truth to reconcile | Peers sharing an account would echo each other's writes to YouTube | Rejected as a transport. Reuse its ledger/tombstone pattern (`$P/sync/apply/SyncChangeLedgerStore.kt`). |
| (e) Scheduled snapshot three-way reconcile only, with no hooks | Changes reach YouTube minutes later, not seconds | Needs full fetches to diff | ~6 pd | Can't tell "removed locally" from "added remotely" without the base snapshot; wrong for playlists | Use its read half (removal detection) inside (a) |

Effort covers phases 1–3 together, excluding the probes. Add 3–4 pd for tests of the pure parts.

## 4. Read-sync performance notes

- InnerTube has no ETags and no continuation diffs; continuations are opaque. Hash snapshots are the cheap check.
- History paging already stops at the first fully-known page. Do the same on every collection.
- Before this work, the existing sync was additions-only:
  - It ran 10 s after app start, throttled to 10 min, with the library every 6 h.
  - There's also a 3 h periodic job and a manual "Sync now".
  - A profile's first sync is a full import.

## 5. Risks

- **ToS and flagging.** This is undocumented API use with scraped Google credentials. yt-dlp's maintainer: "OAuth carries all the same risk that using cookies does."
  - There's no public report of a ban for subscribe or like writes; SmartTube has done this for years.
  - Bot checks and IP blocks do hit signed-in SmartTube users (#4287).
  - Keep write volume at human scale: coalescing, no bulk fan-out, and a cap of ~50 ops per drain round, with pacing.
- **Endpoint churn is a certain cost.**
  - The history so far:
    - Nov 2024: non-TV contexts rejected
    - Jan 2025: create/delete broke
    - Sep 2025: scope and client tightening (ReVanced_Extended#3235)
    - intermittent token disappearance (#6237)
  - Budget about 1 person-day per quarter.
  - A `Rejected` op must never damage local state.
  - Keep parsers name-based, like `YouTubeAccountParsers`.
- **Semantic mismatch.**
  - SmartTube#6106: a like made in a TV context that the web shows but the Data API doesn't.
  - SmartTube#5047: subscribe state not always reflected.
  - Only the probes can show what Google honours today.
- **Echo and duplicates.** Playlist add isn't idempotent on YouTube. Both of these are essential:
  - the import-path discipline
  - `DEDUPE_OPTION_SKIP`

## 6. Open questions (answer them with Phase 0)

1. Do `playlist/create|delete` work with the TV body today? This decides the playlist model.
2. Does `ACTION_MOVE_VIDEO_AFTER` work under TV auth, and does `VL<id>` on TV expose `setVideoId`? If not, reorder stays local-only.
3. Does a full-length watchtime ping from Flow's reporter actually mark a video watched on youtube.com?
4. Does `like/like` on a music track show in VLLM? Does subscribe work for artist channels?
5. Is `history/pause_watch_history` reflected in the account's setting, and can its current state be read (SPaccount_privacy)?
6. Do `SyncDataAccess`, `BackupRepository` and `YouTubeTakeoutImporter` use only import-path repository methods?
