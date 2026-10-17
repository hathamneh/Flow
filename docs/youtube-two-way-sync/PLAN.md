# Two-way YouTube sync: implementation plan

Status: approved by the owner on 2026-10-17. **Both decisions are made:**

1. **Start with Phase 0, the in-app probe page.** Build no write path before its results are known.
2. **Additive Room schema changes are allowed** for this feature:
   - a `youtube_outbox` table
   - `remoteId`/ownership columns on `playlists`

   Make both with a version bump and an AutoMigration. The DB is at version 33 on this branch. This permission covers these additions only. Any other schema change still needs explicit approval (AGENTS.md rule 4).

**Phase 0 is done.** Its results are in [PROBE_RESULTS.md](PROBE_RESULTS.md), and the phases below were adjusted to match on 2026-10-17.

**Phase 1 is built** (2026-10-17): the outbox, the like/subscribe/Watch later hooks and the three-way pull. See "Phase 1 as built" below.

The research behind this plan, with sources, the capability matrix, rejected options and risks, is in [FINDINGS.md](FINDINGS.md). The current state of the branch and the environment notes are in [CONTEXT.md](CONTEXT.md).

## Goal

The Google account is the shared source of truth. Every user action in Flow is mirrored to YouTube, and changes made on YouTube or on other Flow installs signed in to the same account show up in Flow. The app must stay smooth and fast: no "sync everything on each change", no polling floods.

## Architecture (option a: local-first + persisted coalescing outbox)

```
UI ──> repository user-facing method ──> Room/DataStore write (optimistic, instant)
                                    └──> YouTubeOutbox.enqueue(mutation)  (coalesced by key)
                                              │
                                   YouTubeOutboxWorker (unique work, CONNECTED, backoff)
                                              │  one call per op, withActiveAuth (401 → refresh, retry once)
                                              ▼
                                         YouTube InnerTube (TVHTML5 + Bearer)
                                              │ Sent → update remote snapshot
Pull (YouTubeAccountSync) ── light/first-page ──> three-way merge (fresh vs snapshot vs local)
                                              └── pending outbox keys mask the pull
```

### Components (all under `app/src/main/java/io/github/aedev/flow/data/youtubeaccount/`)

1. **`YouTubeMutation`** is a sealed `@Serializable` type. Every member is a set-state op with a coalescing key, so last write wins per key:

   | Mutation | Coalescing key |
   |---|---|
   | `Subscribe(channelId, on)` | `sub:<channelId>` |
   | `Rate(videoId, LIKE\|DISLIKE\|NONE)` | `rate:<videoId>` |
   | `PlaylistItem(playlistId, videoId, add)` | `pl:<playlistId>:<videoId>` |
   | `PlaylistRename(playlistId, name)` | `plname:<id>` |
   | `PlaylistSave(playlistId, on)` | `plsave:<id>` |
   | `PlaylistCreate(localId, name)` | (no key given) |
   | `PlaylistDelete(id)` | (no key given) |
   | `HistoryRemove(videoId)` | (no key given) |
   | `HistoryClear` | (no key given) |
   | `HistoryPaused(on)` | (no key given) |
   | `MarkWatched(videoId, durationMs)` | (no key given) |

   A like followed by an unlike collapses to one op, or to none if the result equals the remote snapshot. `Rate(NONE)` is sent as `like/removelike`, which clears a like and a dislike alike; `like/removedislike` is 404. There is no `Bell` mutation: the probe showed the bell can't be written under TV auth.
2. **`YouTubeOutbox`** is backed by a new Room table `youtube_outbox`. It's in the same transaction as the local write where practical. Columns: key (PK), payload JSON, enqueuedAt, attempts, lastError. It belongs to the profile automatically, because `flow_database` is a profile-owned file (see `data/profile/ProfileFiles.kt`). A DataStore fallback is acceptable if a transactional write is impractical at a call site.
3. **`YouTubeOutboxWorker`** runs as unique one-time work `flow_youtube_outbox`.
   - Policy `APPEND_OR_REPLACE`, network CONNECTED, exponential backoff. It's enqueued on every mutation.
   - It drains FIFO in one bounded loop: at most ~50 ops per round, with light pacing.
   - The outcomes:

     | Outcome | Handling |
     |---|---|
     | `Sent` | Drop the op and update the snapshot. |
     | `Retry` (IOException/5xx/429) | Backoff; give up after 7 days. |
     | `Rejected` (400/403) | Drop the op and increment a "N actions couldn't sync" counter shown on the account page. Local state is never rolled back. |
     | `SignedOut` (`needsSignIn`) | Keep the op queued. |

   - The worker must no-op when the active profile has no account. WorkManager's DB is shared across profiles.
   - Model it on the Last.fm scrobble queue: `data/scrobble/ScrobbleStore.kt`, `Scrobbler.flush()`, `ScrobbleWorker`.
4. **New `YouTubeAccountApi` write calls.** Use the same `innerTube()` request builder: TV context, Bearer, `X-Goog-Pageid`. See FINDINGS.md §2 for the endpoints and bodies.
   - Add `next(videoId)` for like and subscribe state (done in Phase 0).
   - Add `historyFeedbackToken(videoId)`, which walks at most 3 FEhistory pages.
5. **Hook points: the repositories' user-facing methods only.** Never hook the import paths.

   | Repository | Methods to hook |
   |---|---|
   | `SubscriptionRepository` | `subscribe`, `unsubscribe` |
   | `LikedVideosRepository` | `likeVideo`, `dislikeVideo`, `removeLikeState` |
   | `PlaylistRepository` (data.local) | `addVideoToPlaylist`, `removeVideoFromPlaylist`, `updatePlaylistName`, `unsaveExternalPlaylist`, `addToWatchLater`, `removeFromWatchLater`, create/delete |
   | `ViewHistory` | `clearVideoHistory`, `clearAllHistory`, `markAsWatched` |
   | `PlayerPreferences` | `setWatchHistoryPaused` |

   The import paths stay silent: `subscribeAll`, `importLikes`, `syncSavedPlaylistVideos`, `bulkSaveHistoryEntries`, backup restore, Takeout import, and `sync/apply/SyncApplier` / `SyncDataAccess`. That's what prevents echo loops. Before hooking, audit `SyncDataAccess`, `BackupRepository` and `YouTubeTakeoutImporter` to confirm they only use the import methods. If one doesn't, give the repository an explicit `mirror = false` path.
6. **`YouTubeRemoteSnapshot`** keeps, per collection (subs, likes, WL, each playlist), the ordered id list last seen on YouTube plus a hash of its first page. The pull then becomes a three-way merge:

   | Comparison | Meaning | Action |
   |---|---|---|
   | fresh ∖ snapshot | added remotely | Add locally through an import path. |
   | snapshot ∖ fresh | removed remotely | Remove locally, unless a pending outbox op exists for that key. |
   | local ∖ snapshot, no pending op | pre-outbox local change (migration) | Leave it alone. |

   Removals are only detected within the walked window. Document that.

### Playlist identity

- Today, YouTube playlists are stored as `isUserCreated=false` saved rows, and every pull overwrites them through `replacePlaylistVideos`. Phase 2 changes that:
  - The account's **own** playlists become editable rows with `remoteId` and ownership set.
  - The pull **merges** instead of replacing.
- Flow-created playlists are created online first when possible, and the returned id is stored in `remoteId`. Offline, queue a `PlaylistCreate`.
- If the Phase 0 probe shows `playlist/create` is broken under TV auth (likely), Flow-created playlists stay local with a "Flow only" badge, as in SmartTube.

### Read-sync: fast, not "everything"

- **Foreground light round** (already delayed 10 s after start; throttle at 5 min or more): fetch only the first page of each of FEhistory, FEchannels, VLWL and VLLL, stopping at the first known id. That's about 4 calls. If the first-page hash is unchanged, skip the DB work.
- **Screen-open refresh:** a collection's screen refreshes only that collection, if its snapshot is older than 15 min. Pull-to-refresh forces a full walk of that collection. Playlists are refetched only when their aggregation tile (count, title or thumbnail) changed.
- **Heavy walk:** keep the existing 6 h library cadence and the 3 h periodic job.
- **Per-item read-through (Phase 4, measure first):** one `/next` per playback for like and subscribe state, gated by more than 1 h of staleness.
- YouTube has no push mechanism for this. Other installs see a change on their next foreground light round.

## Phases

### Phase 0: probes (1–2 person-days). Do this first.

Add a hidden debug-only "YouTube sync diagnostics" screen under account settings. The user taps it, and it runs each check with the signed-in token **inside the app**, then reads the result back and shows a pass/fail table.

1. `like/like` + `removelike` on a known video. Read back through `/next` and VLLL.
2. `subscription/subscribe` + `unsubscribe` on a test channel. Read back through FEchannels.
3. Watch Later add and remove through `browse/edit_playlist` (WL).
4. On a throwaway playlist:
   - add with `dedupeOption: DEDUPE_OPTION_SKIP`
   - remove
   - `ACTION_SET_PLAYLIST_NAME`
   - `ACTION_MOVE_VIDEO_AFTER`, with `setVideoId` read from a `VL<id>` browse
5. `playlist/create` and `playlist/delete`, with both TV and WEB bodies.
6. `feedback` with a "remove from history" token from FEhistory.
7. `history/pause_watch_history` and `resume_watch_history`, and whether the state can be read back.
8. Bell params from `/next`, then `notification/modify_channel_preference`.
9. `like/like` on a YouTube Music track. Read back through VLLM.
10. One WEB_REMIX browse with the TV token. The expected result is HTTP 400.
11. A full-length watchtime ping (`st=et=cmt=len`). Check for a watched bar on youtube.com; the user verifies this manually.

The checks must clean up after themselves. Record the results in `docs/youtube-two-way-sync/PROBE_RESULTS.md` and adjust the phases accordingly. **Never take the user's token out of the app or handle it in a shell.**

### Phase 1: outbox + likes, subscriptions, Watch Later (3–4 pd)

All of these passed the probe, including dislikes and likes on music videos, which land in Liked music.

- Build the schema change, the outbox and the worker. The API writes already exist from Phase 0 (`YouTubeAccountApi.rate`, `setSubscribed`, `editPlaylist`).
- First, audit `SyncDataAccess`, `BackupRepository` and `YouTubeTakeoutImporter` (FINDINGS §6 question 6).
- Hook the like, dislike, subscribe and Watch Later methods.
- Use the snapshot three-way pull for those three collections.
- Show the "couldn't sync" status line.

#### Phase 1 as built

- **Audit (FINDINGS §6 question 6).** Not every import path was silent:
  - These now pass `mirror = false`: `SyncDataAccess.writeLikes` (like, dislike, clear) and its unsubscribe, and the backup restore's `likeVideo`.
  - The YouTube pull writes through the silent paths only: `importLikes`, `subscribeAll`, and `addToWatchLater`/`removeFromWatchLater` with `mirror = false`.
  - `YouTubeTakeoutImporter` was already silent (`subscribeAll` and the DAO).
  - These do mirror, on purpose: `WatchLaterCleanup` (otherwise the pull would put watched videos back), `LikedMediaUseCase` take/restore likes (bulk remove and its Undo), and Watch later takes, restores and adds through the generic playlist methods.
- **Hook.** `data/local/LibraryMirror` is a plain listener, the same pattern as `ViewHistory.progressListener`. The like and subscription stores are created outside Hilt, so an injected listener couldn't reach them. `YouTubeOutbox.start()` sets it from `FlowApplication`.
- **Outbox.**
  - Table `youtube_outbox`: DB 33 → 34, AutoMigration, schema `34.json`. Its key is (accountId, key), so a change waits for the account it was made under.
  - Changes are queued only while an account is active. A guest's changes stay local.
  - A like, a subscribe or a Watch later add is dropped when the last pull already shows it. Removals and dislikes are always sent, because YouTube's lists don't show dislikes.
- **Worker.** `YouTubeOutboxWorker` runs as unique work `flow_youtube_outbox` with APPEND_OR_REPLACE, CONNECTED and a 30 s exponential backoff.
  - Each round sends up to 50 changes, 300 ms apart, and queues another round when more are left.
  - Outcomes: 408/429/5xx/IOException retry; other 4xx are rejected; 401 after a refresh, or no token, keeps the change queued. A change older than 7 days is dropped and counted.
  - A round starts at app start (10 s delay) and whenever an account is signed in.
- **Snapshots.** `YouTubeRemoteSnapshot` is DataStore `youtube_sync_state`: profile-owned, install-only, not in backups.
  - It holds an id list per account and list (subs, likes, liked music, WL), plus the rejected count.
  - After a sent removal, the id is dropped from the snapshot. Additions wait for the next pull to show them, because YouTube decides whether a like lands in Liked videos, Liked music or both.
- **Pull.** `YouTubeLibraryMerge` handles subs, likes, liked music and WL with `remoteDiff`. The rules:
  - Queued keys mask both additions and removals.
  - Subscription removals need a complete walk; `browsePages` now reports whether it reached the last page.
  - Likes and WL also accept removals within the read window: old index + new ids < read size − 3.
  - An empty read removes nothing, because a parser break looks the same. So a list emptied on YouTube isn't mirrored.
  - A channel unsubscribed in Flow at or after the last snapshot isn't re-added.
  - Each list's walk and merge runs under `YouTubeOutbox.exclusive`, so a send can't land between the read and the merge.
- **Cadence.**
  - The light sync (≥ 10 min apart) now also reads the first page of VLLL, VLLM and VLWL: 3 more requests.
  - The full library walk keeps its 6 h cadence.
- **Status line.** The phone and TV account pages show "N changes couldn't sync" (tap to dismiss) and "N changes waiting to sync" (tap to try now), only while there's something to show.
- **Not done in Phase 1:** reordering Watch later. Phase 2 does it with `setVideoId` moves.

### Phase 2: playlists (3–4 pd)

- **Create and delete are dead** (HTTP 400 with TV and WEB bodies, with and without `privacyStatus`). Flow-created playlists stay local with a "Flow only" badge; deleting one only deletes it locally.
- Make the account's own playlists editable, using `remoteId`/ownership.
- Add (with `DEDUPE_OPTION_SKIP`), remove, and reorder via `setVideoId` + `ACTION_MOVE_VIDEO_AFTER` all passed on Watch Later. Ship them for own playlists.
- Rename is unverified. Before shipping it, run a one-off rename-and-restore probe on one of the owner's existing playlists, with their approval.
- Save and unsave someone else's playlist (`like/like|removelike` with a playlist target): add it to the probe first.
- Change the pull from replace to merge.

### Phase 3: history (2–3 pd)

- **Blocker first (cause found):** the TV `/player` call was UNPLAYABLE ("The page needs to be reloaded") because it sent no `signatureTimestamp`. It now sends the decoder's; confirm with a probe run. Before the fix: the TV `/player` response had no `playbackTracking` for the owner's account. That stops watched pings and the probe's history-removal check. It may also mean the existing history upload (`YouTubeWatchReporter`) silently does nothing. Find out why from the probe's `playabilityStatus` note, and fix it, before the rest of this phase.
- Remove from history, using an on-demand feedback-token lookup (still unproven live).
- Clear history.
- Pause and resume are **Flow → YouTube only**: the calls are accepted, but the account's state can't be read back, so it's never pulled.
- Mark watched through a full-length ping, once `/player` gives tracking URLs again.
- Move the reporter's first "open" ping onto the outbox, so a failed ping isn't lost.

### Phase 4: read-through state (1–2 pd)

- The bell is **dropped**. `modify_channel_preference` is 400; re-subscribing with the bell params is accepted, but it isn't reflected. The bell stays local to Flow.
- Staleness-gated like and subscribe state on playback; `/next` reads both reliably.
- Measure fetch counts per playback in logcat before enabling by default.

### Out of scope

"Not interested" and the notification bell stay local to Flow. YouTube Music library writes aren't possible: the TV token is rejected by WEB_REMIX. Comments are out.

## Rules to keep

- AGENTS.md:
  - Hilt constructor injection
  - no new `getInstance()`
  - strings in `values/strings.xml` only
  - M3 tokens
  - file size budgets
  - ktlint/spotless ratchet
  - flavor-prefixed Gradle tasks
  - one fetch per cause
  - bounded concurrency on `PerformanceDispatcher.networkIO`
- Unit-test the pure parts:
  - mutation coalescing
  - three-way snapshot diff
  - conflict masking by pending ops
  - outcome classification
  - parsers for `setVideoId` and feedback tokens
- After each phase:
  - run `spotlessCheck`, `:app:testGithubDebugUnitTest`, `:app:compileFossDebugKotlin`
  - build `:app:assembleGithubSync`, install it on the phone and TV, and exercise the flow
- Commit per phase with `type(scope): …` messages, push to the `hathamneh/Flow` fork, branch `feature/google-account-sync`.
