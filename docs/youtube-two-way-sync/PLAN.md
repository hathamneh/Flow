# Two-way YouTube sync: implementation plan

Status: approved by the owner on 2026-10-17. **Both decisions are made:**

1. **Start with Phase 0, the in-app probe page.** Build no write path before its results are known.
2. **Additive Room schema changes are allowed** for this feature:
   - a `youtube_outbox` table
   - `remoteId`/ownership columns on `playlists`

   Make both with a version bump and an AutoMigration. The DB is at version 33 on this branch. This permission covers these additions only. Any other schema change still needs explicit approval (AGENTS.md rule 4).

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
   | `Bell(channelId, state)` | (no key given) |

   A like followed by an unlike collapses to one op, or to none if the result equals the remote snapshot.
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
   - Add `next(videoId)` for like, subscribe and bell state.
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
- **Per-item read-through (Phase 4, measure first):** one `/next` per playback for like, subscribe and bell state, gated by more than 1 h of staleness.
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

- Build the schema change, the outbox, the worker and the API writes.
- Hook the like, subscribe and Watch Later methods.
- Use the snapshot three-way pull for those three collections.
- Show the "couldn't sync" status line.

### Phase 2: playlists (3–4 pd)

- Make the account's own playlists editable, using `remoteId`/ownership.
- Support add, remove and rename; save and unsave someone else's playlist; and create/delete according to the probe results.
- Change the pull from replace to merge.

### Phase 3: history (2–3 pd)

- Remove from history, using an on-demand feedback-token lookup.
- Clear history; pause and resume history in both directions.
- Mark watched through a full-length ping.
- Move the reporter's first "open" ping onto the outbox, so a failed ping isn't lost.

### Phase 4: bell + read-through state (2 pd)

- Bell, using `/next`-based params.
- Staleness-gated like and subscribe state on playback.
- Measure fetch counts per playback in logcat before enabling by default.

### Out of scope

"Not interested" stays local, in Flow's own engine. YouTube Music library writes aren't possible: the TV token is rejected by WEB_REMIX. Comments are out.

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
