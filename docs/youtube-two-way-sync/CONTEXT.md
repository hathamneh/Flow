# Branch context for the two-way sync work

This describes the fork `hathamneh/Flow`, branch `feature/google-account-sync`. The upstream remote is `Flow-Tube/Flow`.

## Already built on this branch

- **Google sign-in** uses the YouTube TV device-code OAuth flow, in `data/youtubeaccount/`.
  - The client id/secret are read from youtube.com/tv. With the Cobalt user agent they're inline in the page.
  - The refresh token is sealed with `KeystoreSecretBox` in DataStore `youtube_accounts`. Multiple accounts are supported.
  - `YouTubeAccountApi` uses TVHTML5 + Bearer + `X-Goog-Pageid` and covers: device code, token, refresh, revoke, `accounts_list`, `/player` tracking URLs, browse, ping.
- **History upload** is done by `YouTubeWatchReporter` through stats pings. It covers videos (through `ViewHistory.progressListener`) and songs (through `Media3MusicService`).
- **The pull**, in `YouTubeAccountSync` and `YouTubeLibrarySync`, brings in:
  - history (FEhistory)
  - subscriptions (FEchannels)
  - likes (VLLL) and liked music (VLLM, fed to `MusicBrainEngine`)
  - Watch Later (VLWL)
  - playlists (FEplaylist_aggregation, four at a time)

  It reports progress through `YouTubeAccountSync.progress`. The worker is `YouTubeAccountSyncWorker`: once per process, with a 10 s delay.
- **Profiles**, in `data/profile/`, give one profile per account plus Guest.
  - `ProfileFiles` parks the profile-owned files under `no_backup/profiles/<key>/`. Those files are `flow_database*`, most of `files/datastore`, and two shared_prefs.
  - `ProfileSwitcher` restarts the process; the files are swapped in `FlowApplication.attachBaseContext`.
  - **Any new store or table is profile-owned by default** unless it's listed in `SHARED_STORES`.
- **UI:**
  - TV: a rail profile button and the "Who's watching?" panel (switch, add, remove).
  - Phone: the account sheet in settings (`AccountSwitcherSheet.kt`), a Google sign-in card on the onboarding welcome step, and the account settings pages.
  - Both: `ProfileStatusOverlay`, which shows the switching screen, the import card and "Watching as".
- **The `sync` build type** has release performance (R8, AOT-able) and is installed over the debug app id `io.github.aedev.flow.debug` with the debug signature. It's branded "Flow Sync" with a blue icon.

## Environment

- `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`
- adb is at `~/Library/Android/sdk/platform-tools/adb`.
- **Devices:**
  - Phone: Pixel 9 Pro, `adb-47161FDAP005E4-15ySVE._adb-tls-connect._tcp`, arm64 APK.
  - TV: Chromecast with Google TV, `192.168.0.157:41485` (run `adb connect` first), **armeabi-v7a** APK.
- **Build and install:**
  - Build: `./gradlew :app:assembleGithubSync`. The APKs are in `app/build/outputs/apk/github/sync/`.
  - After installing, run `adb shell cmd package compile -m speed-profile -f io.github.aedev.flow.debug`. TV cold start is ~1.7 s.
- **Checks:** `./gradlew spotlessCheck :app:testGithubDebugUnitTest :app:compileFossDebugKotlin`. 4137+ tests, all green.
- **Never hold or handle the user's Google tokens from the shell.** Any live API verification runs inside the app, triggered by the user.
- **Commits** follow `type(scope): description` and end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. PR descriptions are brief, with Why and How sections and no test plan.
