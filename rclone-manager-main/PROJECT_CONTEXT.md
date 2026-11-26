# Rclone Manager — Project Context (handoff for a new chat session)

> ## v0.10.1 — Remote locking (needs RCAPI >= 1.3.0)
> - Server side: per-remote PIN (bcrypt, `data/locks.json`), `POST /remotes/unlock` -> session token, 423 for locked remotes; sessions are checked only when a request starts, so running transfers are never cut.
> - Core: session token kept in memory only (`api.rs`, header `X-Remote-Session`, cleared when URL/token change); `session.unlock` / `session.lock` core methods; `remotes` returns `{remotes, locked, unlocked}`. Adapter profile got a `lock` object, `unlock`/`lock` endpoints and `remotesLocked`/`remotesUnlocked`/`settingsLocked`/`unlockSession` fields (older saved profiles fall back to the built-in definitions).
> - Backups send `X-Rcm-Backup: 1` (tokio task-local `BACKUP`), so they run on locked remotes without a PIN (server may forbid per remote).
> - UI: lock / open-lock icon in the drawer, PIN dialog (`Dialogs.pin`) when opening a locked remote or on HTTP 423, Settings -> "Remote lock": session = on app restart (ttl 0, token dies with the process) or idle 5/15/30/60 min (sent as `ttl`), plus "Lock all remotes now". Cached listings/thumbnails are not cleared on lock.
> - Log lines for API errors now include the HTTP status, e.g. `ApiException 502`.

> ## v0.10.0 — Rust core (READ FIRST; supersedes the Kotlin API/backup/ops notes below)
> The non-UI core now lives in **Rust** (`core/`, crate `rcm-core`, built as `librcm_core.so`, arm64 only). The UI stays native Kotlin.
> - **In Rust:** adapter profile + API client (`adapter.rs`, `api.rs`, reqwest + rustls, HTTP/2), backup engine + glob filters (`backup.rs`, `glob.rs`), file operations (`ops.rs`), disk caches and thumbnail fetch (`cache.rs`, `thumbs.rs`), job store (`jobs.rs`), client log + job logs (`logs.rs`), cancel-by-tag (`cancel.rs`).
> - **Still Kotlin:** all Activities/views, Media3 player (+ `VideoCache`), bitmap decoding and memory LRU (`Thumbs`), AlarmManager/`Scheduler`/`Conditions`, `BackupService` (notifications, wake lock), `Prefs`.
> - **Bridge:** one JNI function `Core.call(method, argsJson, listener)` (`bridge.rs`) routed by `dispatch.rs`; Kotlin wrapper `Core.kt` (`Core.run/string/long/array`). Errors become `ApiException(status, msg)`, `AdapterException`, `IOException("Canceled")`. Progress goes through `CoreListener` (`onBytes`, `onProgress`, `onBackup`).
> - **Kotlin facades keep their old names** so the UI is almost untouched: `Api`, `Ops`, `BackupRunner`, `DiskCache`, `ClientLog`, `JobLog`, `JobStore`. `Adapter.kt` was removed (now `adapter.rs`).
> - **Wire behaviour is unchanged:** same URLs, URL encoding (OkHttp rules), headers, `User-Agent: okhttp/4.12.0`, JSON bodies, timeouts, HTTPS-only, 404/415/401 handling. The video player still uses OkHttp (`Api.playerClient()`), fed with the same headers/timeouts by the core.
> - **Known differences:** TLS trust uses bundled Mozilla roots (webpki), not Android's system store (a private CA will not work); no per-write socket timeout (an in-flight stall watchdog + HTTP/2 keep-alive pings replace it); size text in job logs is formatted by the core ("1.50 MB"); cancel tags are strings.
> - **Build:** CI installs Rust + `cargo-ndk`, runs `cargo test` (non-blocking) and `cargo ndk -t arm64-v8a` into `app/src/main/jniLibs/`. `build.yml` **changed** (do not keep the old one). `core/README.md` has details.
> - **Unverified:** neither the Rust nor the Kotlin changes were compiled by the assistant. Expect compile errors on the first CI run; paste `error[E…]` (Rust) or `e: file:///…` (Kotlin) lines.
> - Comments were tidied to English; stage-2 markers became TODOs.

> ## v0.9.0 (latest)
> - **Seek bug:** `SeekParameters.CLOSEST_SYNC` snapped every seek to the previous key frame (15 s video: 3 s→0, 13 s→8). Now `EXACT`.
> - **ERROR_CODE_FAILED_RUNTIME_CHECK (fixed in 0.9.1):** log showed `IllegalStateException: Playback stuck buffering and not loading`: the 8 MB byte cap stopped loading before a high-bitrate video had 1 s buffered. LoadControl is now min 2.5 s / max 15 s with `prioritizeTimeOverSizeThresholds(true)` (8 MB cap only applies after the 2.5 s minimum). Earlier note: root cause not confirmed. `PlayerActivity` now logs the full error and auto-retries (level 1 = without the disk cache, level 2 = also default buffering). Check Settings → Client logs for the stack trace.
> - **Slideshow removed** (image viewer + player).
> - **Settings is a page** (`SettingsActivity`): server (inline URL/token/Connect), adapter (`AdapterActivity`, full-screen JSON editor), loop, cache limit + clear + live memory, logs, version. `Dialogs` now only has `prompt`. Layouts: `activity_page`, `row_setting`, `row_switch`.
> - **Client log** (`ClientLog.kt`, `LogActivity`): filesDir/client.log (~400 KB), tokens scrubbed, uncaught-crash hook. Written by Api (failed calls), Files (list errors), Image, Player, Ops, Backup, Settings.
> - **Three-dot menu** on every file and folder (list + grid): Open, About, Rename, Copy to…, Move to…, Back up now (device files), Delete. `MediaActions.copyMove/delete` take `isDir`. Long-press / selection mode is still not built.

> ## v0.8.0 (supersedes the v0.7.0 notes where they differ)
> - **Adapter (`Adapter.kt`, `Api.kt`):** a JSON profile edited in-app (drawer → "Adapter"; stored in prefs `adapter`, "Reset default" restores the built-in `rcapi-v1`). It defines URL `prefix`, `auth` (`bearer` | `header` name/prefix | `query` name), extra `headers`, `timeouts`, response `fields` (names + `timeFormat` iso/epochMs/epochSec) and every endpoint (`method`, `path`, `query`, JSON `body`) with placeholders `{remote} {path} {name} {dest} {destRemote} {isDir} {modtime} {id}`. Required endpoints: remotes, settings, list, stream, thumbnail, mkdir, rename, delete, copy, move, upload. Changes apply immediately (clients are rebuilt), no new APK.
> - **Disk cache (`DiskCache.kt`, `VideoCache.kt`):** folder listings (`lists/`, shown first then refreshed), viewer photo originals (`files/`), Media3 `SimpleCache` for video (`video/`). Limit `Prefs.cacheMb` (100/300/1000, default 300; half files, half video). Thumbnails keep `thumbs/` (100 MB). Settings → "Cache & memory…" shows memory (Java/native/graphics), cache sizes, clear all.
> - **Memory:** video RAM buffer 12 MB (10/30 s), thumbnail LRU 6 MB, thumbnail pools 4+2, photo decode max 1600 px. Photo viewer preview (`Thumbs.peek`) is cache-only (never waits for the server).
> - **Video loop:** `PlayerActivity.maybePrefetch()` caches the first 6 MB during the last 7 s when looping, so the restart does not show a spinner.
> - **Photo viewer:** originals are stored on disk, one retry, and the toast now shows the real error text.
> - **Server (rcapi) now uses one long-running `rclone rcd`** (`lib/rcd.ts`): list/stat/mkdir/delete/copy/move/rename, `/stream` (proxy of `--rc-serve`, Range works) and thumbnails (download via rcd, video frames by ffmpeg reading ranges over HTTP, thumbnail stored with `operations/copyfile`). CLI remains only for the streaming `rcat` upload and `touch`. `RCD_FLAGS` env tunes rcd.

> ## v0.7.0 — API-client migration (READ FIRST; supersedes the sections it names below)
> The app is now a **client of the RCAPI server** (Bun + ElysiaJS + rclone, `rcapi.zip`). **There is no local rclone anymore.**
> - **Removed:** `librclone.so`, `RcServer`/`Rc`, `RcloneRunner`, rclone.conf import, `Thumbs` remote generation, `.thumbnails` lookup, Go/NDK steps in CI, jniLibs/legacy packaging, arm64-only filter. (§2 rclone row, §3 build steps 2-3, §4.1-4.3, §7 are obsolete.)
> - **Added deps:** `okhttp 4.12.0`, `media3-datasource-okhttp 1.5.1` (HTTP/2 over the server's Caddy TLS).
> - **`Api.kt`:** single client (`Api.client`, Bearer token interceptor, 16 conns/host). `list` (GET /files), `remotes`, `settings` (GET /server/settings), `streamUrl`/`download` (GET /stream, Range), `thumbnail` (GET /thumbnails/{id}, id = base64url("remote:path")), `mkdir/rename/delete/copy/move` (POST /operations/*), `upload` (streaming **PUT /operations/upload?remote=&path=&modtime=**). `Api.cancel(tag)` aborts tagged calls. `Remotes` caches remote names in prefs. HTTPS only (`network_security_config`).
> - **Server setup UI:** drawer item "Server" → `Dialogs.server` (URL + 64-hex token; saved only if `/server/settings` succeeds; stores remotes + `maxUploadBytes`). Prefs: `serverUrl`, `token`, `remotes`, `maxUpload`.
> - **Thumbnails (`Thumbs.kt`):** remote = memory LRU → disk cache → `Api.thumbnail` (server generates/stores `.thumbnails/<name>.webp`); failures are not retried for 5 min. Device files are still decoded locally (ImageDecoder / ThumbnailUtils) because the server has no copy.
> - **Backup (`BackupRunner.kt`, replaces rclone):** walks `job.source`, lists each remote dir via `/files`, uploads when missing / size differs / local mtime > remote mtime + 2 s, `sync` deletes remote leftovers (skips files excluded by filters; aborts if source unreadable). Include/exclude use `Glob` (rclone-like: `*`, `**`, `?`, `{a,b}`, `[abc]`, unanchored = any depth). `transfers` = parallel uploads, 3 retries per file. `BackupService` cancels through `AtomicBoolean` + `Api.cancel(BackupRunner.TAG)`. Exit codes: 0 ok, 1 some files failed, 130 cancelled, <0 setup error.
> - **`Ops.kt`:** device↔device via java.io; server↔server via `/copy` `/move` (server-side); device→server = streaming uploads; server→device = downloads to `*.part` then rename. `OpRunner.run(a, title, work, done)` (no `rc` flag any more).
> - **Server changes (rcapi):** new `PUT /operations/upload` (raw stream → `rclone rcat`, then best-effort `rclone touch` with the device mtime, then background thumbnail); default `MAX_UPLOAD_MB=20480`. The multipart POST upload still exists.
> - **Not changed:** `.github` — use the provided trimmed `build.yml` (no Go/rclone); stage-2 file operations in the list are still not built (§9/§10 unchanged).
> - Unverified (not compiled/run by the assistant): everything above. Expect small compile errors; report `e: file:///…` lines.


Version at time of writing: **0.6.0** (`versionName`), package `app.rclonemgr`.
Companion file: the source zip (`rclone-manager.zip`). Give the assistant **both** at the start of the next session.

---

## 0. How to use this file

Paste this at the start of the next session, together with the zip:

> Read `PROJECT_CONTEXT.md` and the attached source zip. Continue the project from there.
> Reply in Indonesian (UI strings, logs and code stay English), keep answers short (mobile screen), and give me the updated zip when code changes.
> Next task: …

The assistant **cannot compile** in its sandbox (no network, no Android SDK). Every build error comes back from the user's GitHub Actions log as `e: file:///...` lines. Treat all new code as unverified until the user confirms the build is green.

---

## 1. Product summary

An Android app that is an **rclone manager**:

1. **Auto backup to remote** (jobs, triggered by *interval* or by *time of day + weekdays*, running in the background, no resident service).
2. **File browser** (Material-Files-like UI) for **internal storage (home)** and for **rclone remotes**, with thumbnails (grid/list), a photo viewer and a video player.
3. Later: file operations (selection mode, cut/copy/paste, delete, rename, new folder) — **not done yet, see §10**.

### Hard requirements / constraints (from the user)
- `minSdk 30` (Android 11), `targetSdk 35`, **Material Design 3**.
- Lowest possible resource usage: idle ≈ 0 MB (process is killed when nothing is running), peak while working ≤ **150 MB** in total (app + rclone child process).
- Only **arm64-v8a** (armv8).
- Built by **GitHub Actions** (the user works from a phone: GitHub web + Termux git). Release APK signed with the user's own keystore.
- UI and logs in **English**. The conversation with the user is in **Indonesian**.
- UX should resemble **Material Files** (zhanghai/MaterialFiles, GPL-3.0). We only imitate layout/features; **no code or resources were copied** (if code is ever copied, the project becomes GPL-3.0).

---

## 2. Tech stack

| Item | Value |
|---|---|
| Language | Kotlin 2.1.20 (K2 compiler — strict about some deprecated APIs) |
| AGP / Gradle | 8.9.2 / 8.11.1 (Gradle installed in CI by `gradle/actions/setup-gradle`, no wrapper in repo) |
| compileSdk / targetSdk / minSdk | 35 / 35 / 30 |
| UI | Views + Material Components 1.12.0 (no Compose, no Room, no coroutines, no ViewModel) |
| Other deps | appcompat 1.7.0, swiperefreshlayout 1.1.0, viewpager2 1.1.0, **media3-exoplayer 1.5.1**, **media3-ui 1.5.1** |
| rclone | Built in CI from source for `android/arm64` (cgo via NDK), shipped as `app/src/main/jniLibs/arm64-v8a/librclone.so` (it is an executable, not a library) |
| Java target | 17 |
| Release build | `isMinifyEnabled = true`, `isShrinkResources = true`, lint does not abort the build |

Why `librclone.so`: Android 10+ forbids executing files from app data; only `nativeLibraryDir` is executable. Therefore `android:extractNativeLibs="true"` + `packaging.jniLibs.useLegacyPackaging = true` + `keepDebugSymbols += "**/librclone.so"`. The binary is run with `ProcessBuilder` from `applicationInfo.nativeLibraryDir`.

---

## 3. Build, signing, CI

### Workflow (`.github/workflows/build.yml`)
1. checkout, Java 17, Go (stable).
2. Resolve rclone version (input `rclone_version`, empty = latest GitHub release), cache the binary by version.
3. Build rclone: `CGO_ENABLED=1 GOOS=android GOARCH=arm64`, `CC=$NDK/.../aarch64-linux-android30-clang`, `-buildmode=pie -tags noselfupdate`, output to `jniLibs/arm64-v8a/librclone.so`.
4. Gradle 8.11.1 → `gradle assembleRelease --no-daemon`.
5. Upload `app/build/outputs/apk/release/*.apk` as artifact `rclone-manager-arm64`.

**Important:** the user's repo copy of `build.yml` was edited on GitHub (decode-keystore step that fails loudly if the secret is empty, plus an `apksigner verify --print-certs` step). The zip contains the *original* `build.yml`. **Never overwrite the user's `.github` folder** when updating (see §12).

### Signing
`app/build.gradle.kts` reads env vars `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`; without a keystore it falls back to the debug key (which changes every CI run → "app conflict" on update).
- Secrets live in **Repository secrets** (not *Environment* secrets — those need `environment:` in the workflow): `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` (= `rcm`), `KEY_PASSWORD`.
- Keystore `release.jks` was generated with `keytool` in Termux and must be backed up by the user. Never commit `*.jks`, `*.b64`, `*.zip` (`.gitignore` has them).
- `versionCode = GITHUB_RUN_NUMBER`.

---

## 4. Architecture

### 4.1 Process model (resource strategy)
- **No always-running service.** Background work is driven by `AlarmManager`:
  `Scheduler` → alarm → `AlarmReceiver` → `BackupService` (foreground, type `dataSync`) → `RcloneRunner` (child process `librclone.so copy|sync …`).
- After the last job finishes and no activity is visible, `BackupService.onDestroy()` **kills the app process** (`Ui.visible` is a counter of started activities, kept by `BaseActivity`). So background idle memory is 0.
- Browsing uses **one** local rclone daemon (`RcServer`, `rclone rcd`) that exists only while a browse/viewer screen needs it (ref-counted, killed 60 s after the app goes to background unless a player/operation "pins" it).

### 4.2 Backup path
- `Job` (JSON in `filesDir/jobs.json`, `JobStore` singleton cache): id, name, source (absolute local path), remote, remotePath, sync (copy|sync), trigger (INTERVAL=0 / TIME=1), intervalMin (≥15), hour, minute, days bitmask (bit0=Mon…bit6=Sun), wifiOnly, chargingOnly, exclude (one pattern per line), include (one per line), transfers (1–8, default 4), enabled, lastRun, lastStatus.
- `Scheduler`: exact alarm (`setExactAndAllowWhileIdle`) when `canScheduleExactAlarms()` else `setAndAllowWhileIdle`. `nextTrigger()` computes next time (interval = now + N min; time = next enabled weekday at HH:mm).
- `AlarmReceiver`: for `ACTION_RUN` it **reschedules first**, then checks `Conditions` (network, Wi-Fi/unmetered, charging). If unmet → status `Deferred: …`, retry in 30 min (only if that is sooner than the next regular trigger). Otherwise `startForegroundService(BackupService)`. Other actions (BOOT_COMPLETED, MY_PACKAGE_REPLACED, TIME_SET, TIMEZONE_CHANGED, exact-alarm permission change) → `scheduleAll()`.
- `BackupService`: single-thread executor (jobs run sequentially → one rclone at a time), partial wake lock (6 h), notification with progress (last rclone stats line, throttled to 2 s) and a Cancel action; result notification per job. Handles Android 15 `onTimeout`.
- `RcloneRunner.run()` flags: `--transfers N --checkers max(2,N) --buffer-size 4M (2M if N>4) --use-mmap --max-backlog 500 --retries 2 --low-level-retries 5 --stats 3s --stats-one-line --stats-log-level NOTICE`, filters via a temp `--filter-from` file (excludes first, then includes, then `- **` if there are includes). Env: `GOMEMLIMIT=96MiB`, `GOGC=50`, `HOME=filesDir`, `TMPDIR=cacheDir`.
- `JobLog`: per-job log file, limits `STORE_LINES=200` (on disk), `SHOW_LINES=80` (dialog), `RUN_LINES=80` per run (stats lines are collapsed to one "last progress" line), `MAX_LINE=300`.
- Remotes come from `filesDir/rclone.conf`, which the user **imports** (file picker copies it; it overwrites the old one — a merge mode was offered but not built). `RcloneRunner.remoteInfo()` parses `[name]` + `type =`.
- Source folders need **All files access** (`MANAGE_EXTERNAL_STORAGE`) because rclone needs real paths.

### 4.3 Browse path (`rclone rcd`)
`RcServer` starts:
`librclone.so rcd --config … --cache-dir … --rc-addr 127.0.0.1:<random port> --rc-user rcm --rc-pass <random> --rc-serve --buffer-size 2M --use-mmap` (env as above). `awaitReady()` polls the socket. `RcServer.auth` is a Basic header. Cleartext to `127.0.0.1` is allowed by `res/xml/network_security_config.xml` (needed by Media3 / MediaMetadataRetriever / HttpURLConnection).

`Rc` client:
- Listing: `POST /operations/list` `{fs:"remote:", remote:dir, opt:{noMimeType:true}}`, parsed with a streaming `JsonReader` into `Entry(name,size,isDir,modified)`.
- File URL: `http://127.0.0.1:<port>/` + `Uri.encode("[remote:dir]", "/:")` + `/` + `Uri.encode(name)` (rcd `--rc-serve` form `[remote:path]/file`, supports Range).
- `Rc.get(url, readTimeoutMs)`, `Rc.readBytes(url,max)` (small files, keep-alive), `Rc.readHead`, `Rc.post(path, json)` generic.
- rc commands used: `operations/list`, `operations/stat`, `operations/mkdir`, `operations/deletefile`, `operations/purge`, `operations/copyfile`, `operations/movefile`, `sync/copy`, `sync/move`, `job/status`, `job/stop`, `core/stats` (long operations run with `_async:true` and are polled).

Lifecycle: `RcServer.acquire(ctx)/release()` (ref count), `pins` (>0 while a player or long operation runs), `ensureRunning()` (called from `BaseActivity.onStart`), `stopLater()` (called from `BaseActivity.onStop` when no activity is visible; kills after 60 s unless pinned).

### 4.4 Model
- `Loc(remote: String?, path: String)` — `remote == null` means a local absolute path; otherwise path relative to the remote root. `child(name)`, `fs()` (rclone fs string).
- `Entry(name,size,isDir,modified)` with `ext` and `kind` (DIR, IMAGE, VIDEO, FILE, AUDIO, ARCHIVE, DOC by extension sets).
- `MediaHolder` (static): `names`, `images: List<Entry>`, `videos`, `changed` (viewer deleted/moved something → file list reloads in `onStart`).
- `Clipboard` (static): `dir`, `items`, `cut` — **defined but not yet used by the UI**.

---

## 5. Source file map (`app/src/main/java/app/rclonemgr/`)

| File | Role |
|---|---|
| `FilesActivity` | **Launcher / main screen.** Drawer (storages with free space, standard folders, remotes, import rclone.conf, backup jobs, settings), toolbar with search + "View and sort" popup, breadcrumb, `SwipeRefreshLayout` + `RecyclerView` (list/grid), `FastScrollView`, permission state, folder-pick mode (`pick=true` extra). Contains the **stage-2 hooks** (§10). |
| `FileAdapter` | List/grid adapter (selection state via `isActivated`, check badge, thumbnail loading through `Thumbs.load`, optional `menuBtn`). |
| `NavAdapter` | Drawer adapter (`NavItem` kinds ITEM/DIVIDER/HEADER). |
| `Thumbs` | Thumbnail pipeline (see §7). Also `peek()` for the photo viewer. |
| `ThumbImageView` | 16:9 ImageView for grid thumbnails. |
| `FastScrollView` | Custom thin line + draggable handle on the right edge (shows while scrolling, fades after 1.2 s). Attached with `attach(rv)`. |
| `ImageActivity` | Photo viewer: **ViewPager2** (`PageVH`, preload neighbours, page transformer, margin), per-page thumbnail preview → 250 ms crossfade to the full image (max 2048 px), tap hides/shows bars, ⋮ menu, slideshow (3.5 s, smooth scroll). |
| `ZoomImageView` | Matrix-based pinch/pan/double-tap zoom; `onTap`; disallows parent intercept while zoomed. |
| `PlayerActivity` | **Media3 / ExoPlayer** video player, custom controls (see §8). |
| `MediaActions` | Shared ⋮-menu actions for viewers: `PickFolder` contract, About, Move/Copy to folder, Delete file, Back up now (remembered destination), Cast (system cast panel). |
| `OpRunner` | Runs an operation on a thread with a progress dialog + Cancel (acquires/pins `RcServer`). |
| `Ops` | File operations: local via `java.io`, remote via rc jobs (`mkdir`, `createFile` (local only), `delete`, `rename`, `paste`). |
| `Rc` / `RcServer` | See §4.3. |
| `RcloneRunner` / `JobLog` | Backup process runner + per-job log. |
| `BackupService`, `AlarmReceiver`, `Scheduler` (+`Conditions`) | Background backups. |
| `Job`, `JobStore`, `JobsActivity`, `JobEditActivity` | Backup jobs data + UI (folder picker via SAF tree → path conversion `Paths.treeUriToPath`). |
| `BaseActivity` | Dynamic colors, edge-to-edge, `Ui` counter, `RcServer` ensure/stopLater. |
| `Prefs` | SharedPreferences `prefs`: `loop` (default true), `grid`, `thumbFolder` (true), `generate` (true), `hidden`, `sortAsc` (true), `dirsFirst` (true), `sortBy` (0 name,1 type,2 size,3 modified), `backupDest`. |
| `Dialogs` | Name prompt, Settings dialog (thumbnail folder / generate / loop / clear thumbnail cache). |
| `Model`, `Util` | Data classes + helpers (`edgeToEdge`, `applyInsets`, `applyOverlayInsets`, `dp`, `Ui`, `Paths`). |

Resources: layouts `activity_files/jobs/job_edit/image/player`, `item_file_list/grid`, `item_image_page`, `item_nav*`, `item_crumb`, `item_job`, `row_option`, `dialog_input`, `chip_day`; menus `files_main`, `viewer`, `edit` (and an unused `player.xml`); ~60 vector drawables (icons use `?attr/colorControlNormal` tint; `*_w` icons are white); `bg_*` drawables; `xml/network_security_config.xml`.

---

## 6. UI behaviour details worth remembering

- **Home** = internal storage (`Environment.getExternalStorageDirectory()`); other mounted volumes appear via `StorageManager.storageVolumes`.
- **Hidden files** (names starting with `.`) are hidden unless "Show hidden files" is on. The `.thumbnails` folder is therefore hidden by default.
- **Sort**: name (Collator) / type / size / last modified, ascending toggle, folders first. Remote listing requests ModTime.
- **"View and sort" is a custom `PopupWindow`** (rows from `row_option.xml`, own radio/check vector icons). Reason: Android's `MenuItem.setChecked(false)` on an *exclusive* checkable item **checks** it, which broke the menu radios; and the stock radio/checkbox views are not aligned.
- **Edge-to-edge** everywhere: transparent bars, `decorFitsSystemWindows=false`, `layoutInDisplayCutoutMode=SHORT_EDGES` (otherwise hiding the status bar shifts content by the notch height). Normal screens use `applyInsets(root, appBar)`; full-screen viewers use `applyOverlayInsets` (content is never padded; only overlays use *stable* insets) so the video/photo never moves when bars toggle.
- **Pick-folder mode**: `FilesActivity` started with `pick=true` shows only folders and reuses `pasteBar` as a "Select" bar; returns `remote` + `path` extras.
- Search filters the current folder only (not recursive).

---

## 7. Thumbnails (important, many iterations)

Order: **memory LRU (8 MB) → disk cache (`cacheDir/thumbs/<md5>.webp`, ≤100 MB, trimmed to 80 % every 40 writes) → `.thumbnails/<file name>.webp` in the same directory → generate from the original.**

- Cache key = md5(`remote:path/name|size|modified`).
- **Convention chosen by the user:** for `dir/video.mp4` the thumbnail is `dir/.thumbnails/video.mp4.webp` (always webp). Works for local and remote. Raw bytes are cached without re-encoding. The user reports this is very fast.
- Pools (LIFO queues, so what is on screen loads first): `fast` 4 threads (disk cache, `.thumbnails`, local generation), `slowImg` 3 threads, `slowVid` 2 threads. `Thumbs.cancelAll()` clears queues (called on every folder load).
- Local generation: `ImageDecoder` (target ≈320 px, software allocator) or `ThumbnailUtils.createVideoThumbnail`.
- Remote generation: photo = download the **whole file** (≤30 MB) to `cacheDir/thumb*.tmp` then decode (JPEG > 1 MB: after 192 KB an EXIF thumbnail is shown as a quick low-res preview, not cached); video = `MediaMetadataRetriever` over the local HTTP server with the Authorization header, 1 retry (Android's HTTP source has a ~30 s timeout; very large videos, e.g. 400 MB with `moov` at the end, can still fail → use `.thumbnails`). Read timeout for generation is 45 s.
- Settings can disable `.thumbnails` lookup and/or generation.
- `Thumbs.peek(ctx, loc, entry)` returns an existing thumbnail (memory/disk/`.thumbnails`) without generating — used by the photo viewer for the blur-to-sharp transition.

---

## 8. Media viewers

### Photo viewer (`ImageActivity`)
ViewPager2 with `offscreenPageLimit = 1`, `itemViewCacheSize = 0`, 2-thread load pool, local = decode directly, remote = temp file then decode (max 2048 px). Each page: `preview` ImageView (thumbnail) under `ZoomImageView`; crossfade 250 ms. Page transformer: 16 dp margin, scale/alpha fade of off-center pages. Tap toggles bars (immersive). Menu (⋮ via `PopupMenu.setForceShowIcon`): About, Back up now (local files only), Move to folder, Copy to folder, Slideshow, Cast, Delete file.

### Video player (`PlayerActivity`, Media3 1.5.1)
- `ExoPlayer` with `DefaultHttpDataSource` (connect 30 s, read 60 s, `Authorization` header), `DefaultLoadControl` 15 s min / 50 s max / 1.5 s start / 3 s after rebuffer, **32 MB buffer cap**, `prioritizeTimeOverSizeThresholds=false`, `SeekParameters.CLOSEST_SYNC`. Replaced `VideoView` because MediaPlayer stalled at the end of its buffer.
- Custom controls (Google-Photos-like): play/pause, mute, small **Save as photo** button (PixelCopy of the SurfaceView → MediaStore `Pictures/RcloneManager`), time text, `SeekBar` with buffered position (`secondaryProgress = bufferedPosition`).
- Gestures on a transparent `touch` view: single tap = show/hide bars (title bar, controls and system bars hide together); **double-tap right/left = ±10 s** with accumulating "+20s" bubbles (more taps within 0.7 s keep skipping); double-tap center = play/pause; **hold left/right 35 % = 2x**, release restores the chosen speed.
- ⋮ menu (9 items): About (adds duration/resolution), Loop video on/off (global pref, default on), Playback speed (0.25–2x), Back up now (local only), Move to folder, Copy to folder, Slideshow (plays next video when one ends), Cast (opens the *system* screen-cast panel; no Google Cast SDK), Delete file (from remote or device). "Edit in Acode" and "Help & feedback" were removed on purpose.
- Bars auto-hide 3.5 s after playback starts.

---

## 9. Feature status

### Done
- Backup jobs (interval / time+weekdays, Wi-Fi-only, charging-only, transfers 1–8, include/exclude filters, copy/sync, logs with limits, boot/time-change rescheduling, notification with cancel).
- Material-Files-like main screen (drawer, breadcrumb, list/grid, sort popup, search, hidden files, pull-to-refresh, fast scroller, copy path).
- Browsing local storage and remotes; fast thumbnails; photo viewer; Media3 player with gestures; folder-pick mode; viewer actions (About, Back up now, Move/Copy, Delete, Slideshow, Cast).
- rclone.conf import (overwrite).

### Not done — "stage 2" (user asked to postpone until requested)
Selection mode and file operations in the file list: long-press selection, cut/copy/paste (local↔remote), delete, rename, new folder/file, properties, per-item ⋮ menu, select all.
**What is already prepared:**
- `Ops` (mkdir/createFile/delete/rename/paste) and `OpRunner` are written (used today by the viewers).
- `Clipboard` object, icons (`ic_cut`, `ic_copy`, `ic_paste`, `ic_delete`, `ic_close`, `ic_check`, `ic_more`), `fab` (visibility gone) and `pasteBar` (ids `pasteText`, `pasteBtn`, `pasteCancel`) in `activity_files.xml`, item layouts with `check` badge and `menuBtn` (visibility gone), adapter selection support (`selected()` callback, `isActivated`).
- Markers in `FilesActivity.kt`: stub `onItemLong(e)`, stub `showItemMenu(e, v)`, comment `// stage2: menu branches` inside `onMenu()`, comment `// ---- stage2 insert point ----`, `setMode(sel)` currently ignores `sel` (needs a `files_select` menu: cut, copy, delete, rename, select all), `selected` set and `ioOps` executor exist but are unused.
- **Caveat:** `pasteBar` is also used by pick-folder mode as the "Select" bar (`setupPickBar()`); stage 2 must not break that (e.g. use different texts/visibility rules per mode).

### Ideas / backlog (not requested yet unless noted)
- Merge mode when importing `rclone.conf` (add/replace remotes instead of overwrite).
- Skip a job trigger if the same job is already running/queued ("Skipped: already running").
- Thumbnail generation: Wi-Fi-only option, max-size option, optionally write generated thumbnails back to `<dir>/.thumbnails/<name>.webp` on the remote.
- Configurable video buffer (e.g. 15/30/60 s) in Settings.
- Material Files' "Only for this folder" sort option; recursive search; share/open-with for other file types; audio/document viewers.
- 12 h/24 h time format follows system (currently picker is fixed 24 h).

---

## 10. Known issues, risks, unverified assumptions

- Nothing here has been compiled by the assistant. Always expect small compile errors.
- **rclone rc usage is untested on device** beyond listing/serving: `operations/stat`, `copyfile`, `movefile`, `sync/*`, `job/*` (used by viewer actions and `Ops`). Errors surface as toasts.
- Memory numbers are estimates. Measured earlier: app process ≈ 76 MB while uploading (rclone child counted separately). Browsing adds one `rclone rcd` (~50–80 MB) + thumbnail threads; Media3 buffer capped at 32 MB.
- Mega remote is slow/latency-heavy: photo thumbnail generation downloads whole files; huge videos may fail to produce a frame.
- Cast = system screen cast only.
- `Back up now` only appears for local files; remembered destination stored in `Prefs.backupDest` (no UI to reset except choosing "Change folder").
- Local-to-local paste overwrites silently; remote copy overwrites (rclone behaviour).
- `FilesActivity` handles configuration changes itself (`configChanges`), so state survives rotation but there is no saved-instance restore after process death.

---

## 11. Lessons learned / gotchas (do not repeat)

**Kotlin / build**
- `FloatArray.indexOf(Float)` is a compile **error** in recent Kotlin → use `indexOfFirst { it == x }`.
- `File.createTempFile(prefix, …)` needs a prefix of **≥ 3 chars** (a 2-char prefix silently broke all photo thumbnails).
- Avoid `by` as an identifier.
- Duplicate classes from stale files (e.g. old `BrowseActivity.kt` with an old `FileAdapter`) break the build — see §12.
- Media3 `@UnstableApi` is only a lint issue (lint is non-fatal here).

**Android**
- Exclusive-checkable menu items: `setChecked(false)` checks them; groups without ids share id 0. Use a custom popup (done).
- Hiding the status bar on devices with a notch moves content unless `layoutInDisplayCutoutMode=SHORT_EDGES`; do not pad the content view with window insets in full-screen viewers.
- On Android < 15 edge-to-edge is not enforced → `edgeToEdge()` must be called explicitly (otherwise the theme paints the status bar).
- `Slider` has no buffered track; `SeekBar.secondaryProgress` does.
- `VideoView` (MediaPlayer) stalled at the end of its buffer over the local HTTP server; Media3 fixed it.
- `HttpURLConnection` default read timeout is too short for slow remotes; set explicitly.
- Thumbnails behind a slow video retriever starved photos when they shared a pool → separate pools.

**GitHub / git (user works from a phone)**
- Secrets must be **Repository secrets**. `apksigner verify` shows `CN=Rclone Manager` when the release key is used.
- Pushes made by GitHub Actions with `GITHUB_TOKEN` do not trigger other workflows (the old "Unzip" workflow trick needed a manual Build run). The user now uses Termux + git.
- Git over HTTPS needs a **Personal Access Token** (classic, scopes `repo` + `workflow`) as the password; `git config --global credential.helper store` remembers it.
- If `git push` is rejected (non-fast-forward): `git pull --rebase`, then push.
- Termux must come from F-Droid/GitHub; `pkg install openjdk-17` provides `keytool`.

---

## 12. How to update the repo from a new zip (safe procedure)

`cp -a` only adds/overwrites; it **never deletes** files that were removed in the new version. Use:

```bash
cd ~/rclone-manager                      # the user's repo folder in Termux
git pull --rebase
find . -mindepth 1 -maxdepth 1 ! -name .git ! -name .github -exec rm -rf {} +
rm -rf /tmp/x
unzip -q ~/storage/downloads/rclone-manager.zip -d /tmp/x
F=$(find /tmp/x -name settings.gradle.kts | head -1)
TOP=$(dirname "$F")
rm -rf "$TOP/.github"                    # keep the user's edited workflow
cp -a "$TOP"/. .
git status                               # no *.zip / *.jks / *.b64 allowed here
git add -A
git commit -m "Update"
git push
```

Leftover files known to exist in the user's repo and harmless if still present: `res/menu/player.xml`, possibly `res/layout/row_radio.xml` / `row_check.xml` (old, unused).

---

## 13. Working agreements with the user

- Reply language: **Indonesian**; app strings/log lines/code comments: **English**.
- Mobile-friendly, concise answers; lead with the answer; short lists; give the **zip** (via the file card) after code changes and say which files/areas changed.
- The user tests on a real device and reports precise symptoms (screenshots, build logs). When a build fails the user pastes the `e: file:///…` lines.
- The user wants Material Files-like UX, low resource usage, and explains new work in small steps; do **not** start stage 2 (selection/paste/rename/new folder) until asked.
- Before changing behaviour that the user could not describe well, first explain the likely causes and tests (they asked for this approach for the video-stall problem).

---

## 14. Suggested first steps in the next session

1. Confirm the zip builds (ask for `e:` lines if not) and that the app is at 0.6.0.
2. Ask the user what to do next (likely: **stage 2**, §9).
3. If implementing stage 2, start from the markers in `FilesActivity.kt` and reuse `Ops` / `OpRunner` / `Clipboard`; add `res/menu/files_select.xml`; keep pick-folder mode working.
