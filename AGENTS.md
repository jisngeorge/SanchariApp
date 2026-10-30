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

The shared theme uses blue branding and green primary-action buttons (Find Bus,
Get Started, proceed, and submit). Use `Widget.Sanchari.Button.PrimaryAction`
for these actions; its theme-aware selectors preserve disabled states.
Blue headers keep white text/icons in both themes; use `brand_header` rather
than the lighter night-mode `colorPrimary` for header backgrounds. Preserve
red not-running and amber slow-service warning colours.
Keep the system status bar visible so users can read the clock. Its background
uses `home_background`, with dark icons in light mode and light icons in dark mode,
including on Android versions that enforce a transparent edge-to-edge status bar.

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
- Timetable times are displayed as `h:mm AM/PM` in service results, route stops
  (including both ends of a halt), and the edit/new-service stop editor.
  `TimetableTimeFormatter` changes presentation only; blank/unknown times remain
  unchanged. The editor uses a 12-hour picker, but models, drafts, sorting,
  calculations, and contribution JSON retain 24-hour `HH:mm` values.
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

The main page is a scrollable, search-first layout: route-search card, bus-name
search card, Recent Services, then app actions. Preserve its original wording;
do not add headings, taglines, descriptions or an icon beside the app title.
Wildcard/clear controls retain separate 48dp targets; swapping stops does not
submit a search. Drafts & submissions uses a labelled button with the history icon.
Update availability uses a green highlight without changing the Update label.
Home surfaces and outlines adapt to light/dark mode; content grows with text size.

`BusSearchHandler` delegates SQL work to `SearchManager`. Route search matches
source and destination location names and requires source `stopOrder` to precede
destination `stopOrder`. Its two `NOT EXISTS` clauses exclude pairs with another
occurrence of either endpoint between them; preserve this repeated-stop behaviour.
Results are ordered by source scheduled-time text. Name search uses a partial
`LIKE` match, limits results to 20, and is debounced by 150 ms in the UI.

Route search also accepts a whole-field `*` in exactly one input. `A -> *` evaluates
each distinct visit to A as an independent outbound leg. Start in `stopOrder` at
that departure, treating A as visited and ignoring all earlier route history,
including history before the first A. Consecutive rows with the same place name
count as one visit, even with different stop IDs. Use only the last row of an A
halt for departure and the first row of a destination halt for arrival:
`A, A, B, C, D` yields one A-to-D result from the second A.
Within each leg, the first return to a place after visiting a different place
is a hard boundary. Ignore every stop at or after that return for this leg,
even if a new place appears later. Use the last visit before the boundary as the
destination. If no return occurs, use the final visit's first arrival row.
Departures with no later different place are excluded; later A visits start new
legs with fresh history and may reuse earlier destination names.
Thus `A, K, L, M, M, L, K` ends at the first M, and
`C, A, D, F, F, D, C` ends at the first F. `A, B, C, C, D` continues to D.
`A, B, C, D, D, C, B, A, K, A` yields the first A-to-D leg and a later A-to-K leg;
K does not replace the first D. `Origin, A, B, Origin` now ends at the later Origin.
For `A, B, C, C, B, A, A, D, E, E, D, A, A, B, C, C, B, A`,
with times t1 through t18, return A-to-C at t1-t3, A-to-E at t7-t9, and
A-to-C at t13-t15. The final A has no onward result.
`* -> A` mirrors this logic backwards independently from each A visit, ignoring
all history after that arrival. Use the first A row of its halt as arrival and
the last row of the selected origin halt as departure. Scan backwards until the
first non-consecutive repeated place; ignore that row and every earlier row for
this leg. If no return occurs, use the actual first visit's last departure row.
Exclude arrivals with no earlier different place. The same 18-row example yields
C-to-A at t4-t6, E-to-A at t10-t12, and C-to-A at t16-t18; the initial A has no
inbound result. Repeated arrivals reset their reverse-scan history independently.
All route sequence decisions
follow `stopOrder`, not clock times. Both
wildcards and blank inputs are rejected; embedded asterisks remain literal stop
name characters. Wildcard searches do not expand into every possible stop pair.
`RouteSearchResult` wraps a `BusService` with the displayed endpoint names and
route-stop row IDs, preserving repeated visits even when their times are equal.
`SearchManager.findRouteResults` returns these wrappers; `findBusServices` remains
available for callers that only need the underlying services.

The main From/To fields each show a wildcard button immediately before their clear
button. It replaces only that field with `*` without opening autocomplete or
submitting a search; clear removes only that field. There is no wildcard help
message below the search button. Both-wildcard validation still applies.
Leaving a From/To field validates its full stop name against the loaded stop
suggestions; unknown names show an inline TextInputLayout error. Typing clears
the field's error until it is validated again. Find Bus validates both fields and
blocks invalid searches before querying routes. Blank fields require input on
submission; a whole-field `*` is valid in one field only. Full names are accepted
case-insensitively with outer whitespace trimmed, but route queries and result
titles use the canonical stored spelling (exact-case matches take precedence).
Stop validation does not query SQLite while typing. Suggestions/validation
reload after database updates and before each route search; unavailable stop
data shows an explicit message rather than labelling every name invalid.

Wildcard searches fetch the ordered route rows for matching services in one
bound SQL query. Consecutive place names are collapsed into visits retaining
first-arrival and last-departure rows. A shared linear-time unique-place sliding
window selects independent leg endpoints, scanning the visits backwards for
arrivals. Process one service at a time; do not reintroduce per-result queries or
nested repeated-stop scans. Preserve clock-time result ordering and endpoint row
identities separately from this route-order computation.

`SearchResultsActivity` receives Parcelable route-search results through Intent extras and
opens `BusDetailsActivity`. Details load ordered route stops and community data;
contribution actions are handled by `BusSubmissionHandler`. Non-running services
remain visible, with warning styling rather than being removed from searches.
Bus details reuse the main page's rounded card styling and light/dark surfaces,
preserving existing titles and wording. Stop times sit below stop names so halt
ranges can wrap; rating labels sit above their stars, and comment dates below
usernames. Keep the edit/rating/comment and running-status contribution flows
unchanged, including the ability to add a rating when no aggregate exists.
Wildcard result cards label each endpoint place beside its corresponding AM/PM
time. Ordinary A-to-B results retain compact cards with the service icon/name/type
and times on the right, without repeating From/To place names on every card.
For ordinary A-to-B results, running services with valid journey durations
strictly greater than 1.5 times the currently filtered results' unrounded average
have yellow-orange names, with theme-specific amber shades distinguishable from
red. Use the same timed coverage as the summary, including
non-running services in the average; missing/invalid/identical times are excluded.
Red for not-running services takes priority. Filtering recomputes the threshold
and updates only names whose cached warning state changes; sorting does not
alter the warning state. Statistics aggregate durations in one pass without an
intermediate duration list. Wildcard results never
use the orange warning. The colour is a scheduled-duration warning, not proof of
an alternate route. Name tooltips/accessibility descriptions explain warnings;
the summary tooltip/accessibility description explains the colour meanings.
Only the underlying service is passed to detail/history/contribution flows.

Route results show the service count and average scheduled A-to-B duration
(rounded to the nearest minute) together above the list as
`Services: N   Average time: Hh MMm`. Unavailable averages show `--`.
There is no separate
statistics panel or fastest-service display. Calculations use the currently
filtered results' matched endpoint times, including services marked not running.
`RouteSearchStatistics` accepts strict 24-hour `HH:mm` times, treats earlier arrivals
as next day, and excludes missing, invalid or identical endpoint times. Timed
coverage and exclusions are available through the summary's long-press tooltip
and accessibility description. These are timetable estimates, not live times.
Toolbar icons offer sorting, bus-type multi-selection (with an All types reset),
and alternative stops. The sort dialog includes next-departure ordering alongside
shortest/longest journey and original departure-time order; there is no separate
next-departure icon. `RouteResultsManager`
applies these options without modifying the original search results. Next
departures are ordered from the device's local current minute, wrapping passed
departure times to tomorrow; this does not indicate live availability or
day-specific operation. This order refreshes every minute while the activity is
started and on returning to it. Missing/invalid times sort last. Default ordering
restores the original SQL departure-time order. Sorting and selected bus types
survive activity recreation. The alternative-route bulb remains visible in the
toolbar. Empty filters show an
explicit empty state; the screen remains open so the filter can be reset.

Wildcard results use "Any origin"/"Any destination" titles and show `Services: N`
without a journey average. Duration sorting and alternative-stop suggestions
are hidden for these modes; bus-type filtering remains available. Outbound
wildcard sorting uses departure from the known stop. Inbound sorting uses arrival
at the known stop, labelled "Arrival time"/"Next arrivals first". Invalid times
sort last, and next-arrival ordering wraps to tomorrow and refreshes like
next-departure ordering. Counts refer to eligible result visits, not distinct
service IDs; the summary's tooltip/accessibility description explains this.
Normal A-to-B repeated-stop rules and summary/sort options are unchanged.

`SuggestionManager` computes geographically nearby alternative stops using stored
coordinates and route availability. Despite its name, it does **not** submit edits.
Its selectable, scrollable dialog groups alternatives into "Board near" and
"Alight near" sections, with bold stop names, labelled endpoint distances and
additional/total service counts. Each section ranks eligible stops independently
using 40% additional services, 30% total services, and 30% proximity to its original
boarding/alighting stop. Min-max normalize each factor within that section;
shorter distances score higher, and equal-valued factors contribute equally.
Sort by descending weighted score, breaking ties by shorter distance, more
additional services, more total services, then stop name. Candidate eligibility
and service counting otherwise remain unchanged. Neither original endpoint is
eligible as an alternative stop in either section, including on loop services.
The dialog does not explain the ranking formula.
Distances remain straight-line estimates; additional services are
counted relative to the original A-to-B route. No alternatives show an explicit
empty message instead of blank section headings.

`TimetableDatabaseHelper` is a shared singleton. Callers must not close its shared
database after each query. `SearchManager` also caches stop autocomplete values.
When replacing the timetable file, reset the helper and invalidate this cache.
Community reads use separate helper instances and close their database connections.

## Timetable proposals and spreadsheet uploads

Flow: `SuggestEditActivity` -> `SuggestEditHandler` -> `ConfirmationActivity`
-> `NetworkManager.uploadDataToGoogleSheet`.

The editor supports existing services and new services, editable/reorderable stops,
12-hour AM/PM time selection, notes, and local drafts. Selected times are stored
in 24-hour `HH:mm` format. It preserves existing service IDs
and gives new services an ID of `NEW-<UUID>`. A newly introduced stop is represented
by `"stopId": "NEW"`; existing stop IDs remain integers. Submitted `stopOrder`
is regenerated as a one-based sequence from the editor list.

Stop names have local autocomplete with ID-bearing choices. Users may still enter
new places; these retain the `"NEW"` stop ID. Renaming a stop must not retain an
unrelated existing ID. Duplicate-name choices remain distinct. Bus type is also
an editable local dropdown and permits new values.

The suggestion editor groups service details, route stops, and optional notes.
Each stop uses a card with a header for order/move/remove controls and full-width
outlined name/time fields. Keep Material outlined styles on autocomplete fields
so floating labels have their own space; do not shrink their internal padding.
Time edits highlight the card outline using theme colours, not a fixed background.
Fresh add/edit sessions show a short Quick tips dialog. Opening a saved draft or
recreating the editor skips it.

`Reverse and append` copies any named contiguous section in reverse, retaining
stop identities and halt rows but leaving copied times blank. Reversing A-B-C-D
appends D-C-B-A, including the duplicated turnaround. Time edits never reorder
the route; only explicit up/down actions change the sequence.

`Suggest time` estimates one row from its immediately preceding timed row.
It first averages valid direction-specific A-to-B pairs already in the editor
(including intermediate stops, with normal nearest repeated-endpoint matching,
excluding the target pair), then same-type timetable intervals, then all types.
It does not borrow reverse-direction samples or estimate same-place halt
durations. Existing scheduled interval rules apply, including next-day clock
rollover and exclusion of invalid/identical times. Non-running services remain
eligible timetable samples. The preview shows source and sample count and changes
nothing until explicitly accepted. Accepted values remain `HH:mm`; estimate
metadata is not part of drafts or contribution JSON. Route/type/time changes
invalidate pending estimates. Service identity and draft filename survive editor
recreation.

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
- The editor automatically saves the latest changes on Back, toolbar exit and
  backgrounding, and before opening history or confirmation. Incomplete drafts
  are allowed; untouched empty new editors do not create files. Repeated saves
  retain the filename and service ID. Only explicit confirmed Discard deletes
  work; cancellation and ordinary navigation preserve it.
- Draft writes are atomic and queued with deletions on an application-owned
  coroutine scope, so leaving/recreating the editor does not cancel persistence
  or let a late save recreate a discarded/submitted draft. Unchanged snapshots
  are not rewritten; the saved-content marker survives editor recreation.
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
