# Phase 0 probe results

These are the results of the in-app "YouTube sync diagnostics" page (Settings → Integrations → Google account, debug and sync builds only), run by the owner on 2026-10-17 on a Pixel 9 Pro, signed in to their own Google account with the TV device-code token. The runner logs one line per check to logcat (tag `YouTubeSyncProbe`, with no tokens), and that's where these rows come from.

There were three runs:

| Run | Time | Build | Notes |
|---|---|---|---|
| 1 | 20:11 | first probe build | Found the `removedislike` 404 and the URL-encoded bell params. |
| 2 | 20:34 | fixed build | Like checks were skipped: run 1 had left the test video disliked. |
| 3 | 20:35 | fixed build | Full run, after the owner cleared the dislike. This is the reference run. |
| 4–5 | 21:18, 21:23 | playability build | Broken by the phone: AdGuard's DNS protection began failing lookups partway through (`UnknownHostException`, cached within ms). 52 of 160 lookups failed in a burst test. Cleanup calls failed too (see below). |
| 6 | 21:28 | playability build | With Flow excluded from AdGuard. Same results as run 3. The new playability note gave the `/player` answer. |

## Results (run 3 unless noted)

"Read back" means the change showed on YouTube's own pages: `/next` for state, plus the matching `VL…`/`FE…` list.

| # | Check | Result | HTTP | What the read-back showed |
|---|---|---|---|---|
| 1 | `like/like` | **Pass** | 200 | `/next` INDIFFERENT → LIKE; listed in VLLL |
| 1 | `like/removelike` | **Pass** | 200 | LIKE → INDIFFERENT; gone from VLLL |
| 1 | `like/dislike`, then `like/removelike` | **Pass** | 200 | INDIFFERENT → DISLIKE → INDIFFERENT |
| 1 | `like/removedislike` (run 1) | **Fail** | **404** | The endpoint doesn't exist. `removelike` clears a dislike too. |
| 9 | `like/like` on a music video (`dQw4w9WgXcQ`) | **Pass** | 200 | Listed in VLLM (Liked music) |
| 2 | `subscription/subscribe` | **Pass** | 200 | `/next` subscribed=true; listed in FEchannels |
| 2 | `subscription/unsubscribe` | **Pass** | 200 | subscribed=false; gone from FEchannels |
| 8 | `notification/modify_channel_preference` | **Fail** | **400** | Bell params were taken from `/next` toggle states (URL-decoded in runs 2–3, raw in run 1). It's 400 either way. |
| 8 | Fallback: `subscribe` with the bell params | Not reflected | 200 | Accepted, but `/next` still shows OFF afterwards. A freshly subscribed channel also reads as OFF before the change. |
| 3 | `browse/edit_playlist` WL add | **Pass** | 200 | Listed in VLWL |
| 3 | `browse/edit_playlist` WL remove | **Pass** | 200 | Gone from VLWL |
| 5 | `playlist/create`, TV body | **Fail** | **400** | Also 400 without `privacyStatus` |
| 5 | `playlist/create`, WEB body | **Fail** | **400** | Also 400 without `privacyStatus` |
| 5 | `playlist/delete`, TV and WEB | Skipped | — | Nothing could be created to delete |
| 4 | `ACTION_ADD_VIDEO` + `DEDUPE_OPTION_SKIP` (on WL) | **Pass** | 200 | Added twice → 1 copy; the second add also returns 200 |
| 4 | `setVideoId` from `VL<id>` browse (WL) | **Pass** | 200 | 45 of 45 entries carry one |
| 4 | `ACTION_MOVE_VIDEO_AFTER` + `movedSetVideoIdPredecessor` (WL) | **Pass** | 200 | The order flipped as asked. New WL items are appended at the end. |
| 4 | `ACTION_REMOVE_VIDEO_BY_VIDEO_ID` (WL) | **Pass** | 200 | Gone from VLWL |
| 4 | `ACTION_SET_PLAYLIST_NAME` | Skipped | — | Needs an own playlist. Create is broken and WL can't be renamed. **Still unverified.** |
| 11 | Full-length watchtime ping | **Fail** | 200 | The TV `/player` response has **no `playbackTracking`**, so there's nothing to ping |
| 6 | `feedback` "remove from history" | **Fail** | 200 | Blocked by the same missing `playbackTracking`: the probe couldn't put a test video into history. Token parsing is unit-tested but not proven live. |
| 7 | `history/pause_watch_history` | Unverified | 200 | Accepted. No paused notice on FEhistory; `SPaccount_privacy` browse is 400. |
| 7 | `history/resume_watch_history` | Unverified | 200 | Accepted. The state can't be read back. |
| 10 | WEB_REMIX browse with the TV token | **Pass** (refused as expected) | 400 | Confirms that YouTube Music library writes are out |

Every check cleaned up after itself, except two:
- `removedislike` in run 1 left a dislike, which the owner removed by hand.
- Runs 4–5 lost the network partway through. Run 5 liked the music test video (`dQw4w9WgXcQ`) and then couldn't unlike it. Run 6 skipped subscribe because the account was already subscribed to the test channel (`UC4QobU6STFB0P71PMvOGN5A`, jawed), probably left over from run 4, whose log was lost. Both need removing by hand.

**Run 6 and `/player`:** YouTube answers the account's TV `/player` call with `UNPLAYABLE: The page needs to be reloaded.` The request carried no `playbackContext.contentPlaybackContext.signatureTimestamp`; the codebase already notes that MWEB returns UNPLAYABLE without it. The account `/player` request now sends the decoder's timestamp (`PipePipeNsigDecoder.signatureTimestamp()`, the one `TizenStreamResolver` already uses for a TV client). The next run shows whether that brings back `playbackTracking`.

## What this changes

1. **Likes, dislikes, subscriptions, Watch Later and music likes all work two-way.** That's Phase 1's whole scope. Use `like/removelike` for both un-like and un-dislike; `LikeAction.REMOVE` now does exactly that.
2. **Playlist create and delete are dead** with both bodies and both privacy variants. Flow-created playlists stay local, with a "Flow only" badge, as the plan's fallback said.
3. **Playlist item edits work** (add with dedupe, remove, move by `setVideoId`) on WL. They should work on own playlists, but that's unproven until an own playlist is tested.
4. **Rename is unverified.** Phase 2 needs a one-off rename-and-restore probe on one of the owner's existing playlists, with their approval, before shipping rename.
5. **The bell can't be written** under TV auth. Bell sync is dropped: the bell stays local to Flow.
6. **History pause/resume is write-only.** Flow can send its own toggle to YouTube, but it can't learn the account's state, so it never pulls it.
7. **`/player` returns no `playbackTracking`** for this token. Run 6 showed why: UNPLAYABLE, "The page needs to be reloaded", from the missing `signatureTimestamp`. The fix is in; a re-run must confirm it. Watched pings and "remove from history" are blocked by it. **So is the existing history upload (`YouTubeWatchReporter`), which uses the same call and returns silently when the URLs are missing.** The probe now records the `playabilityStatus` so the next run shows why. This has to be understood before Phase 3, and it's worth checking now whether Flow watches still reach youtube.com history.

Open questions from FINDINGS.md §6:

| # | Answer |
|---|---|
| 1 | No: create/delete fail with TV and WEB bodies. |
| 2 | Yes on WL: `setVideoId` is exposed and `ACTION_MOVE_VIDEO_AFTER` works. |
| 3 | Untested: blocked by the missing `playbackTracking`. |
| 4 | Yes: a music-video like lands in VLLM. Artist subscribe is untested, but plain subscribe works. |
| 5 | Pause/resume are accepted; the state can't be read. |
| 6 | Audited in Phase 1. Device sync and backup restore called the user-facing like/unsubscribe methods; they now pass `mirror = false`. See PLAN.md, "Phase 1 as built". |
