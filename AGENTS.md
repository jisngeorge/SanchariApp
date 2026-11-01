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
  Its first profile read transactionally creates a persisted pseudonymous UUID even
  with no personal details. Existing nonblank UUIDs are preserved; legacy blank UUIDs
  are repaired without discarding contacts. Profile edits do not replace the UUID.
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

The main page is a compact, scrollable search workspace: title and three-dot menu,
route-search card, bus-name input, labelled Add Bus and Drafts actions, then a
compact vertical Recent Services list retaining all five
entries without a repeated viewed-recently caption. Each full-width row has at least
a 48dp touch target, with full names available to accessibility and long-press tooltips.
Search and important actions stay above the fold on a standard 360x800dp phone.
The page scrolls when needed for recent entries, smaller screens, large fonts,
keyboard input or inline errors.
Important actions share a row normally and stack on narrow screens or large fonts.
Preserve its original wording;
do not add headings, taglines, descriptions or an icon beside the app title.
Wildcard/clear controls retain separate 48dp targets; swapping stops does not
submit a search. Add Bus has a green icon; Drafts uses a labelled button with the
history icon and still opens both drafts and submission history.
The overflow labels are Update, Auto-update, Share, Message admin and Version;
Auto-update retains the persisted database-update toggle. Update availability uses a green highlight
and an accessible description on the menu button without changing the Update label.
Home surfaces and outlines adapt to light/dark mode; content grows with text size.

`BusSearchHandler` delegates SQL work to `SearchManager`. Route search matches
source and destination location names and requires source `stopOrder` to precede
destination `stopOrder`. Its two `NOT EXISTS` clauses exclude pairs with another
occurrence of either endpoint between them; preserve this repeated-stop behaviour.
Results are ordered by source scheduled-time text. Name search uses a partial
`LIKE` match, limits results to 20, and is debounced by 150 ms in the UI.
From/To suggestions use separate adapters and start after one character, retaining
Android's case-insensitive prefix/word-prefix matching. Focusing or tapping a
populated stop field and finishing a data refresh reapply its current filter.
Unchanged stop lists do not replace adapters. Bus-name suggestions display the
SQLite substring matches directly, without a second ArrayAdapter prefix filter.
Typing clears obsolete bus rows; cancelled/stale queries, cleared fields and
focus loss cannot restore old results. Fresh matches explicitly open the focused
dropdown. Selecting a bus suggestion does not trigger another search during
autocomplete completion. Pausing dismisses suggestions and cancels bus queries;
resuming refreshes focused inputs. Database lookup failures show explicit errors
rather than looking like an ordinary no-match result. Swapping stops suppresses
filtering so asynchronous callbacks do not reopen dropdowns.
Autocomplete lookups require an installed timetable file and never create an
empty SQLite database while first-run installation is still in progress.

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

The main From/To fields each have a full-width input and a separate row below it
with location, wildcard and clear buttons, each with a 48dp touch target.
Swap uses free space in the From control row rather than a separate vertical row.
The wildcard button replaces only that field with `*` without opening autocomplete or
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

## Location-assisted search and maps

Each home location button offers current device location, manual `latitude, longitude`
entry in a single copy/paste-friendly field,
or showing that field's selected stop in Google Maps. The map option is disabled
for blank/wildcard inputs and otherwise uses existing full-name validation.
Nearby selection fills only the initiating field, without submitting a search or
opening autocomplete.

`StopLocationManager` reads fresh coordinate-valid `Stop` rows, without filtering
by services or the opposite endpoint. Both lookup modes have an editable radius in
kilometres, defaulting to 5km and accepting any finite positive distance whose
metre conversion is finite, without a 25km cap. Include every stop within that
inclusive radius, ordered by unrounded Android geographical distance, then name and stop ID.
Distances are straight-line estimates, not road distances. Preserve duplicate
physical stops and label ambiguous names with their IDs and coordinates.
Stored null/text/malformed/out-of-range coordinates and `(0, 0)` placeholders are
excluded; manually entered `(0, 0)` is valid. Decimal-degree inputs must be finite
and within latitude +/-90 and longitude +/-180.

`AndroidDeviceLocationSource` uses cancellable Android GPS/network listeners, not
Google location APIs or SDKs. Fine/coarse permissions are requested together only
on an explicit current-location action; approximate grants remain usable.
Prefer satellite GPS, allowing 15 seconds before network fallback when available,
with a 30-second total acquisition limit. Accept only fixes timestamped after the
request started, never a silent old last-known location. Denied permissions,
disabled/unavailable providers, lookup failures and empty results have explicit
states with retry/settings or manual-entry alternatives. Location hardware is optional.
Keep the Settings option for enabling device location: the Google Maps-style
one-tap enable dialog requires Google Play services, which this app does not use.

Acquisition and map work are foreground-only and cancelled on leaving the activity.
An open popup remains available when switching apps (for example, to copy
coordinates from Google Maps), with its input/radius intact on return. Backgrounding
still cancels active work and never automatically restarts interrupted lookups.
Device fixes are transient and never saved. Manual coordinate text can survive
activity recreation in UI saved state, along with the radius, but lookup never
restarts automatically. Changing either input clears/cancels outdated results.
Radius adjustments can reuse the current fix within the same foreground popup;
pausing/closing discards that fix.
Do not write coordinates/location history to app preferences, files, databases,
contribution payloads or logs. Nearby-selected stop IDs are map-disambiguation hints:
they move with swapped fields, survive recreation with the matching name, and clear
on incompatible edits/clear/wildcard actions. Route searches remain name-based.
Re-read selected stops before applying them or opening a map; reject changed rows.

Fixed A-to-B results have a Google Maps driving-directions toolbar action, hidden
for wildcard searches. Explicitly supply both stored endpoint coordinates and
`travelmode=driving`; this is not a particular bus service's route geometry.
`StopMapActions` asks users to disambiguate typed duplicate names and never silently
substitutes the first stop for an invalid remembered ID. `MapNavigationHelper`
prefers the Google Maps app, falls back to the same coordinate URL in a browser,
and reports when neither is available. No API key or map SDK is required.

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
an editable local dropdown and permits new values. Town to Town is offered even
when absent from the installed timetable, preserving any existing stored spelling.
Result icons share a two-part bus silhouette: the fallback is grey/black, Deluxe
and Minnal have cream upper sections, and Town to Town is sky blue above cream.
Type-icon matching uses the same trimmed, case-insensitive keys as result filtering.
The home bus-name field disables Material start-icon tint so the general bus
icon retains its two colours in both themes.

The suggestion editor groups service details, route stops, and optional notes.
Each stop uses a card with a header for order/move/remove controls and full-width
outlined name/time fields. Keep Material outlined styles on autocomplete fields
so floating labels have their own space; do not shrink their internal padding.
Time edits highlight the card outline using theme colours, not a fixed background.
Fresh add/edit sessions show a short Quick tips dialog. Opening a saved draft or
recreating the editor skips it, including automatic resumption of a service's
draft. Its four bullet points cover important stops/junctions/terminals,
paired arrival/departure rows using the same name for halts, checking for an
existing service before adding one, and optional notes. They do not explain
visible suggestion controls.

`Reverse and append` copies any named contiguous section in reverse, retaining
stop identities and halt rows but leaving copied times blank. Reversing A-B-C-D
appends D-C-B-A, including the duplicated turnaround. Selecting a time or accepting
a suggested time restores stable time-based ordering and highlights/scrolls to
the edited row. Existing route order supplies midnight rollover; the edited clock
time is placed on the closest fitting day or next day. Missing/invalid times sort
last when other timed rows provide a reference; editing an invalid time or the
only timed row does not reorder. Equal-time rows retain their relative order and
identities. Manual up/down controls still move rows without automatically sorting
again; subsequent time edits rerun automatic ordering.

Each Apply / Proceed tap checks for possible missing stops before confirmation,
for both new and edited schedules; there is no separate analysis button.
Required stop names and valid `HH:mm` times are checked first, before querying
for missing stops. Immediately after these required-field checks, each Apply /
Proceed tap shows a warning popup if any adjacent row interval exceeds eight
hours, including existing, newly added and same-place halt rows. Exactly eight
hours produces no warning; earlier clock times use next-day rollover. The warning
identifies the first affected pair by stop order, name and AM/PM time. Continue
accepts the warning and starts normal missing-stop analysis; Go back returns to
the affected stop for editing. Dismissing or recreating the popup never continues
automatically. Required fields and the reviewed stop snapshot are rechecked on
Continue. The warning is not repeated during asynchronous draft saving. Long
intervals remain allowed in submissions and drafts.
Invalid stop cards have theme-aware red outlines and accessible
error states; missing times show a brief warning, never an automatic dialog or
picker. Corrected rows clear their red outlines, and validation state survives
editor recreation but is not saved in drafts. It
checks consecutive gaps against other services in the installed
timetable, excluding the existing service being edited. Forward nearest-endpoint
visits follow `stopOrder`, not time; consecutive same-place halts are collapsed.
Resolved stop IDs distinguish duplicate names. Alternative ordered sequences
stay separate. Before ranking, omit sequences whose summed straight-line
distances through every intermediate stop exceed 1.5 times the direct endpoint
distance. Both endpoints and all suggested stops need finite, in-range stored
coordinates; nulls, the `(0, 0)` placeholder and zero-distance endpoint pairs
are not eligible. Distances are geographical estimates, not road routing.
Rank the remaining alternatives by distinct same-type supporting services, then total
supporting services, without showing counts or ranking details in the UI.
One scrollable Stop suggestions review groups stops by numbered gaps, showing
endpoint names, stop orders and AM/PM times (`--:--` when blank). Its actions sit
in a fixed footer inside the popup; only the suggestions scroll. Every suggested
stop has Accept/Reject choices, initially Reject. Multiple route options use a
dropdown; switching options clears that gap's decisions rather than mixing
sequences. The review explains that suggestions may be incorrect and unsure
stops can be rejected. Decisions are staged until Continue or Add and edit times;
cancellation changes nothing. Accepted stops retain existing IDs and have blank
times. Continue opens confirmation only when every stop has a valid `HH:mm` time;
otherwise affected cards are outlined in red and the editor stays open.
This is rechecked after analysis (including newly accepted stops) and again
across asynchronous draft saving.
Incomplete times remain allowed in drafts. Add and edit times keeps the editor open
so contributors can use the added stops' time-suggestion bulbs. No matches
proceeds directly to confirmation; lookup failure leaves the
editor open with an explicit retry message. Every tap runs fresh analysis,
including after returning from confirmation. Structural changes or timetable
version changes cancel pending review; time-only edits are retained. Cancelled or
interrupted reviews never navigate automatically, including across recreation.
Analysis metadata is not saved in drafts or contribution JSON; ordinary autosave
and discard remain available without analysis.

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
  submittedBy: { uuid, name?, email?, phone?, place? },
  suggestion: <contribution object>
}
```

Name, email, phone and place are optional for every contribution type; no completed
profile is required. Fields start blank on each fresh confirmation and are editable
without a sharing checkbox. The text above them explains that details can help the
admin reply or clarify doubts; it does not mention the private identifier.
Only nonblank trimmed values entered for this contribution are sent as strings.
Stored contacts are never automatically attached or prefilled. Provided profile
changes are saved before upload; blank fields are omitted from the payload and do
not erase stored contacts. Supplied email
still requires `@` and supplied phone at least ten digits. Labels have no required
asterisks. Edited fields survive confirmation recreation.
`ContributionPayload` retains the wrapper and nonempty `submittedBy.uuid`, without
changing service/stop identifiers or contribution timestamps.
New wrapped contribution JSON uses consistent two-space indentation for manual
inspection, including nested objects and arrays; upload and offline storage use
the same formatted string. Already pending files are not reformatted during retries.
Confirmation shows a compact preview:
The confirmation page also states that acceptance follows review and approved
contributions may take time to go live, without promising approval or a deadline.
Confirmation headings use 18sp and preview/help/review text 16sp, with theme-aware
foreground colours and wrapping layouts that respect the system font-size setting.
schedule proposals use red deletions, green additions and normal unchanged rows,
compared with the installed timetable (not an edited draft). New schedules are
all additions. Bus names use blue and bus types teal, retaining red/green +/-
markers on changed fields. Unavailable originals are explicitly identified without implying
a diff. Other contributions show plain summaries. Preview data is UI-only and
does not change upload JSON. Back returns to the intact editor; opening confirmation
never discards its draft. Contact edits survive confirmation recreation.
Backing out of rating/comment confirmation reopens the input dialog with the
previous ratings, comment text and username-display choice.
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

Comments retain `showUsername`; without a shared display name it is forced to
false. Confirmation shows the public name will be Anonymous, without identifier
wording. The private pseudonymous identifier remains in the payload. With a supplied name, the original display choice
still applies. Hiding the public name does not remove contacts explicitly shared
with the administrator. Do not describe this as untraceable or anonymous transport.
Uploads do not update local timetable or community snapshots immediately.
Coordinate release with processor support for missing personal fields. Google Apps
Script intake and its deployment are outside this repository; Android changes do
not establish that the deployed processor supports this contract.

### Drafts versus pending submissions

- `DraftManager` stores unfinished, unwrapped editor JSON in the private directory
  returned by `context.getDir("suggest_edits_drafts", MODE_PRIVATE)`. UUID filenames
  and `draftId` identify drafts independently of bus name/type. There is one draft
  per exact service ID, including each distinct `NEW-<UUID>` service. Editing an
  installed service resumes its existing draft before loading timetable rows.
  Saves reuse the existing filename; new filenames are stable per service ID,
  and queued saves resolve any competing filename to the same service draft.
- The editor automatically saves the latest changes on Back, toolbar exit and
  backgrounding, and before opening history or confirmation. Incomplete drafts
  are allowed; untouched empty new editors do not create files. Repeated saves
  retain the filename and service ID. Only explicit confirmed Discard deletes
  work; cancellation and ordinary navigation preserve it.
- Successful saves show the same `Draft Saved` toast for Save Draft, Back,
  toolbar exit, backgrounding, history and confirmation navigation. Unchanged
  background snapshots are not rewritten or announced; empty new editors and
  failed saves never show a save-success toast.
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
  Retries resend the saved payload unchanged, including UUID, timestamps, sharing
  choices and any previously queued contacts; they never rebuild it from the current
  profile. New sharing choices do not retroactively modify existing pending files.
- Successful schedule uploads add local submission logs. Other contribution types
  skip schedule-specific logging; submission history does not cover every type.

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
and compares remote APK `versionCode` to `BuildConfig.VERSION_CODE`. The home page
has a persisted, default-enabled "Automatically update timetable and community
data" checkbox. Enabled checks (including manual checks) download newer database
snapshots without asking; disabled checks retain the Download/Later prompt.
Downloads can use mobile data. APK updates always require confirmation, independently
of database updates; the parsed `mandatory` flag is not enforced. This is an app-open
check, not scheduled background work. Checks/downloads are serialized per activity
and cancelled on pause. Failed/interrupted database downloads reset the check
timestamp so the next app-open check can retry. Partial success refreshes suggestions
and preserves the update indicator for any remaining database or APK update.

`DatabaseManager.downloadAndReplaceDatabase` serializes snapshot downloads, stages
each in a unique file in the database directory, and verifies SQLite integrity,
required tables/columns and an integer `DbVersion` equal to the manifest version.
It syncs the staged file and publishes it with an Android same-filesystem atomic
rename, never deleting the installed snapshot first. Failed downloads, validation,
cancellation before publication and failed renames preserve the installed snapshot.
Timetable helpers/cache are reset around publication, and the verified content
version is persisted. User data, drafts and pending uploads are never replaced.

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
Focused unit/device tests cover timetable SQL, editor logic, autocomplete, home
layout, results controls, location picking and map intents. Add focused regression
tests when changing these behaviours. Location tests use controlled permission/fix
sources and intercept external map intents; manually check GPS/network acquisition
and precise/approximate permission UI on relevant Android versions and hardware.
Do not send real contributions to the production spreadsheet during validation.

Useful device scenarios for related changes: first-run asset installation; offline
route/name search; repeated stops and route ordering; proposal save/load/reorder;
failed upload persistence and later retry; manual versus throttled version checks;
snapshot replacement and autocomplete refresh; preservation of local user/history
data; and APK installer handoff.
