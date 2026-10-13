# Sanchari: AI Agent Context

## Purpose and scope

Sanchari is an offline-first Android bus timetable app. Users search local SQLite
timetables, view routes and community feedback, and propose timetable additions or
corrections. Proposals are uploaded to a spreadsheet backend, not applied directly
to the timetable database. Published timetable and community database snapshots
are distributed separately using a version manifest hosted on GitHub Pages.

This repository contains the Android client. The spreadsheet processing backend,
database publishing pipeline, and GitHub Pages manifest are not implemented here.
Although the spreadsheet may be described as an "Excel sheet", the client code
specifically POSTs to Google Apps Script through `uploadDataToGoogleSheet`.

Treat the current source as authoritative; update this context when changing these
flows. Do not infer backend approval rules or spreadsheet layout from the client.

## Stack and project layout

- Single Android application module: `:app`; package/application ID `com.sanchari.bus`.
- Kotlin, AppCompat activities, XML layouts, View Binding, and RecyclerView.
  This is not a Compose, Room, or ViewModel-based application.
- Coroutines and `lifecycleScope` for asynchronous work; OkHttp for HTTP.
- Kotlin serialization for configuration/version models; `org.json` for contribution payloads.
- Gradle Kotlin DSL and version catalog in `gradle\libs.versions.toml`.
  Current versions: Gradle 8.13, AGP 8.13.2, Kotlin 2.0.21.
- Android minimum SDK 24; compile/target SDK 36; Java/Kotlin compilation target 11.
  Android Studio selects Temurin JDK 21 to run Gradle. Use a full JDK 21 for
  command-line builds as well; the compilation target remains 11.

All paths below are relative to the repository root. Kotlin source lives under
`app\src\main\java\com\sanchari\bus`.

| Location | Responsibility |
| --- | --- |
| `data\local` | SQLite helpers/schema, asset installation, file replacement, version preferences, pending uploads |
| `data\manager` | Timetable queries, community reads, local user/history writes, nearby-stop suggestions |
| `data\model` | Domain models, Parcelable navigation/editor models, serializable configuration models |
| `data\remote\NetworkManager.kt` | Version manifest fetch, file downloads, spreadsheet uploads |
| `ui\activity` | Main, introduction, results, bus details, timetable editor, submission confirmation |
| `ui\helper` | Search UI, updates, contribution construction, drafts, pending-upload retry |
| `ui\adapter` | Bus, stop, comment, recent-view, and editable-stop list presentation |
| `app\src\main\assets` | Bundled database snapshots and initial `app_config.json` |
| `app\src\main\res` | Layouts, strings, icons, themes, and FileProvider configuration |

## Local data and ownership

`DatabaseConstants.kt` is the central reference for filenames, table names, column
names, and local user schema version. There are three distinct databases:

| Database | Ownership and tables |
| --- | --- |
| `TimetableDatabase.db` | Bundled/downloaded snapshot: `BusService`, `Stop`, `RouteStop`, `DbVersion` |
| `CommunityDatabase.db` | Bundled/downloaded snapshot: `BusRating`, `UserComment`, `DbVersion` |
| `UserDatabase.db` | Created locally: `User`, `RecentView`, `SubmissionLog`; never replaced by remote updates |

- `BusService.serviceId` is a text identifier shared across timetable, community,
  and contribution data. Preserve identifier compatibility.
- `Stop` contains unique locations and coordinates. `RouteStop` links a service
  to a stop, with `scheduledTime` and `stopOrder`; a stop can occur repeatedly on a route.
- Stop times are strings normally formatted as `HH:mm`; missing displayed times
  use `--:--`. Route sequence is defined by `stopOrder`, not by sorting clock times.
- `BusService` also carries `isRunning` and `lastReportedTime`.
- `BusRating` stores aggregate punctuality, driving, behaviour, and rating count.
  `UserComment` stores service-linked comments and their dates.
- `UserDataManager` manages profile/contact data and UUID, the latest five distinct
  viewed services, and successfully uploaded schedule submission history.
- `UserDatabaseHelper` currently uses schema version 2; its version-1-to-2 migration
  adds `SubmissionLog`.
- `LocalVersionManager` uses `SanchariBusPrefs` SharedPreferences for database
  content versions, dynamic endpoint URLs, first-run state, update availability,
  last successful manifest check, and the latest APK link.

Database **content versions** come from `SELECT version FROM DbVersion LIMIT 1`.
They are separate from SQLiteOpenHelper schema versions and the APK `versionCode`.
Do not use the helpers' nominal version 1 as a timetable/community content version.

## Startup, search, and display

`MainActivity` is the launcher. It sets up search and navigation, shows the
introduction on first run, then initializes data on `Dispatchers.IO`:

1. `DatabaseManager.initializeDatabases` seeds missing endpoint preferences from
   `assets\app_config.json`, copies missing timetable/community databases from assets,
   and syncs their content versions into preferences. Existing snapshots are preserved.
2. It launches `UploadManager.retryPendingUploads` without blocking the rest of initialization.
3. It loads stop autocomplete options and local recent views, then checks for updates.

`BusSearchHandler` delegates SQL work to `SearchManager`. Route search matches
source and destination location names and requires source `stopOrder` to precede
destination `stopOrder`. Its two `NOT EXISTS` clauses exclude pairs with another
occurrence of either endpoint between them; preserve this repeated-stop behaviour.
Results are ordered by source scheduled-time text. Name search uses a partial
`LIKE` match, limits results to 20, and is debounced by 150 ms in the UI.

`SearchResultsActivity` receives Parcelable services through Intent extras and
opens `BusDetailsActivity`. Details load ordered route stops and community data;
contribution actions are handled by `BusSubmissionHandler`. Non-running services
remain visible, with warning styling rather than being removed from searches.

`SuggestionManager` computes geographically nearby alternative stops using stored
coordinates and route availability. Despite its name, it does **not** submit edits.

`TimetableDatabaseHelper` is a shared singleton. Callers must not close its shared
database after each query. `SearchManager` also caches stop autocomplete values.
When replacing the timetable file, reset the helper and invalidate this cache.
Community reads use separate helper instances and close their database connections.

## Timetable proposals and spreadsheet uploads

Flow: `SuggestEditActivity` -> `SuggestEditHandler` -> `ConfirmationActivity`
-> `NetworkManager.uploadDataToGoogleSheet`.

The editor supports existing services and new services, editable/reorderable stops,
24-hour time selection, notes, and local drafts. It preserves existing service IDs
and gives new services an ID of `NEW-<UUID>`. A newly introduced stop is represented
by `"stopId": "NEW"`; existing stop IDs remain integers. Submitted `stopOrder`
is regenerated as a one-based sequence from the editor list.

Schedule suggestion JSON has these fields:

```text
type: "new_schedule" or "edit_schedule"
service: { serviceId, name, type, isRunning, lastReportedTime, fromTime, toTime }
stops: [ { stopId, locationName, scheduledTime, stopOrder }, ... ]
editNotes: string
timestamp: Unix epoch seconds
```

`ConfirmationActivity` collects/saves user details and wraps every contribution:

```text
{
  submittedBy: { name, email, phone, place, uuid },
  suggestion: <contribution object>
}
```

Name, email, and phone are required by the confirmation screen. Current validation
requires `@` in email and at least ten phone digits; place is optional.
The upload is an `application/json` POST to the configured Google Apps Script URL.
The client currently treats any successful HTTP status as upload success without
checking the response body for backend acceptance.

Other contribution types use the same confirmation/upload path:

| `suggestion.type` | Additional fields |
| --- | --- |
| `rating` | `serviceId`, `punctuality_5`, `drive_5`, `behaviour_5`, `timestamp` |
| `comment` | `serviceId`, `commentText`, `showUsername`, `timestamp` |
| `running_status_suggestion` | `serviceId`, `suggestedStatus`, `suggestedStatusBoolean`, `timestamp` |
| `admin_message` | `messageText`, `timestamp` |

The comment anonymity flag controls intended username display, not removal of
`submittedBy` contact details. Do not describe this as anonymous transport.
Uploads do not update local timetable or community snapshots immediately.

### Drafts versus pending submissions

- `DraftManager` stores unfinished, unwrapped editor JSON in the private directory
  returned by `context.getDir("suggest_edits_drafts", MODE_PRIVATE)`. UUID filenames
  and `draftId` distinguish drafts independently of bus name/type.
- `SuggestionStorageManager` stores failed uploads as complete wrapped payloads in
  `filesDir\suggestions\suggestion_<timestamp>.json`.
- Confirmation removes the associated draft only after upload success or successful
  pending-upload persistence. Preserve this protection against lost contributions.
- `UploadManager` retries pending files during `MainActivity` initialization and
  deletes them after successful upload. There is no WorkManager/service-based retry.
- Successful schedule uploads add local submission logs. The logging code assumes
  `suggestion.service` exists; other contribution types do not have that structure.
  Do not assume submission history covers every contribution type.

## Remote versions and downloads

Initial configuration is in `app\src\main\assets\app_config.json`.
The default manifest URL is
`https://jisngeorge.github.io/SanchariVersions/versions.json`.
Look up the spreadsheet and APK endpoints in configuration instead of duplicating
their deployment URLs. Stored endpoint preferences take precedence over asset defaults.

`ServerVersionInfo.kt` defines the manifest contract:

```text
{
  timetable: { version: integer, url: SQLite download URL },
  community: { version: integer, url: SQLite download URL },
  app?: { versionCode: integer, url: APK download URL, mandatory: boolean = false },
  versions?: replacement manifest URL,
  communityData?: replacement spreadsheet upload URL
}
```

The parser ignores unknown keys. Both database entries are required.
`versions` and `communityData` can redirect future requests; `app.url` updates
the stored APK/share link.

`AppUpdateManager` throttles automatic checks to seven days. Manual checks bypass
that interval; the timestamp is saved only after a successful manifest fetch.
It compares each remote database content version to the local preference value
and compares remote APK `versionCode` to `BuildConfig.VERSION_CODE`. Available
updates prompt the user to download or defer. The parsed `mandatory` flag is not
currently enforced.

`DatabaseManager.downloadAndReplaceDatabase` downloads to a cache temporary file,
deletes the installed snapshot, renames the downloaded file into place, resets
timetable connections/cache when applicable, and rereads `DbVersion`.
The `newVersion` parameter is currently unused: the downloaded file's content
version is what is persisted.

Important caveat: despite method comments describing verification/atomic swapping,
the current implementation does not validate the downloaded SQLite schema,
integrity, or expected version before deletion, and has no rollback if rename fails.
Do not assume these safety guarantees exist when modifying the update flow.

APK updates download to `cacheDir\update.apk` and launch the Android installer
using FileProvider. Relevant permissions and provider registration are in
`AndroidManifest.xml`; cache sharing is configured in `res\xml\provider_paths.xml`.

## Guidance for changes

- Preserve offline timetable access and keep user data, drafts, and pending
  submissions independent of remotely replaced snapshots.
- Reuse the existing managers/helpers and schema constants. Keep SQL, network,
  and disk work off the main thread; return to `Dispatchers.Main` for UI changes.
- Keep contribution field names, type strings, identifiers, and timestamp units
  compatible with the external backend. Coordinate contract changes explicitly.
  Proposal timestamps use seconds; local submission logs use milliseconds.
- Trace changes across editor, confirmation, drafts, offline retry, and history;
  a working immediate upload alone is not sufficient.
- Treat profile/contact data and queued payloads as personal information.
  Do not introduce payload logging, credentials, or private user data into the repository.
- Use existing XML/View Binding, Material UI, resources, and Parcelable navigation
  conventions. Do not introduce a new architecture or dependency for a small change.
- Do not overwrite bundled `.db` assets casually. Schema/content changes must be
  compatible with queries and externally published snapshots.
- Leave unrelated local changes alone; do not commit generated builds, caches,
  machine-specific `local.properties`, or incidental IDE files.

## Build and validation

Use the checked-in Gradle wrapper from the project root. Android SDK 36 and a
compatible build JDK must be available; SDK location is environment-specific.
Set `JAVA_HOME` to your full JDK 21 installation for command-line builds, not a
JRE. Gradle 8.13 does not support running on Android Studio's bundled JDK 25.
On Windows PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

Choose the smallest relevant task; connected tests require an emulator/device.
Existing tests are only template arithmetic and application-package checks, not
coverage of timetable SQL, submissions, or updates. Add focused regression tests
when changing those behaviours. Do not send real contributions to the production
spreadsheet as part of automated or manual validation.

Useful device scenarios for related changes: first-run asset installation; offline
route/name search; repeated stops and route ordering; proposal save/load/reorder;
failed upload persistence and later retry; manual versus throttled version checks;
snapshot replacement and autocomplete refresh; preservation of local user/history
data; and APK installer handoff.
