# Morsebook Android app architecture

This draft covers the three-screen, on-device MVP in the [functional requirements](functional-requirements.md). The proposed Android app has one activity for navigation and a foreground service for active playback. Screens can disappear while the listener keeps hearing Morse and the app keeps saving their place.

## Modules and ownership

Gradle builds `:core`, `:cli` and the JVM catalog in `:app`. When adding the Android application, switch `:app` to the Android plugin and depend on `:core` so the app and CLI share the same Kotlin synthesizer. Keep Compose, Android audio APIs and persistence in `:app`, with packages for UI, data and playback. Use constructor injection and share repository instances between the activity and service through an application-scoped container. Retain the project's Java 17 target and JDK 25 build decision. [Project decisions](../decisions.md) · [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations)

| State | Owner | Lifetime |
| --- | --- | --- |
| Book IDs, titles, chapter order and chapter text | Catalog repository over bundled assets | App installation. Give each book and chapter a stable ID across updates. |
| Chapter status and bookmark | Reading repository backed by Room | Across launches. A missing row means unread. The only persisted position is the integer source offset of the audible word’s start. |
| Last book and chapter actually played | Reading repository, in the same Room database | Across launches. Update when audio starts, including automatic continuation. Both Now Playing icons use this record. |
| CW speed and tone frequency | Settings repository backed by Preferences DataStore | Across launches. Save each change and expose the pair as a flow. Use core defaults on first launch. |
| Selected chapter, playback phase (paused, starting, playing), current audible word | Playback controller shared by UI and service in one process | Live session. Initialize paused from durable state on cold start. |
| Back stack, expanded menus and scrolling | Navigation host and Compose | UI lifetime, with saved UI state for recreation. |

Repositories expose observable reads as `Flow` and one-shot writes as suspending functions. Room groups a chapter's transition to partially read and its last-played update in one transaction when playback starts. Keep next-chapter selection as a pure Kotlin function used by the controller for both the Next button's availability and the transition itself. That shared rule is enough domain logic for the MVP. [Data layer](https://developer.android.com/topic/architecture/data-layer) · [Domain layer](https://developer.android.com/topic/architecture/domain-layer)

Keep reading history and settings on the originating device. Configure Android backup rules for the supported OS versions to exclude the Room database and DataStore files from cloud backup and device-to-device transfer. [Android backup rules](https://developer.android.com/identity/data/autobackup)

Validate bundled chapters before shipping. Each must contain at least one playable word. When an updated chapter retains its ID but becomes shorter, resolve an out-of-range bookmark to the last playable word start before displaying progress or resuming.

### Bundled content format

`app/bundled-content/catalog.json` contains a `books` array. Each book has an `id`, `title` and `chapters` array. Each chapter has an `id`, `title` and `textLength`, and its text lives at `<book ID>/<chapter ID>.txt`. Both arrays are in display order. IDs use lowercase letters, digits and hyphens. Keep existing IDs stable because reading state identifies a chapter by its book ID and chapter ID.

Save the chapters as UTF-8 with LF line endings and no BOM. Each text file starts with its original chapter heading, which is part of playback. Set `textLength` to the decoded text's Kotlin `String.length` in UTF-16 code units, including the heading and all whitespace. `BundledCatalogChecks` verifies this value for every bundled chapter. Edits before a saved bookmark can shift its position even if the chapter stays the same length.

`BundledCatalog.loadBooks()` reads the catalog in one asset open. Its `loadChapterText(bookId, chapterId)` method reads one chapter as needed. The JVM build packages `bundled-content` as resources for catalog validation. Use the same directory as an Android asset source directory and pass `assets::open` to the loader when wiring up the app.

## Screens and data flow

Each destination has a ViewModel that combines repository flows and live playback state into an immutable `StateFlow` of screen state. The route collects with `collectAsStateWithLifecycle()` and passes values and callbacks to the screen composable. A tap invokes a ViewModel action. Playback actions reach the controller, and the resulting state flows back to every observing screen. A screen may stop collecting at any time. The service keeps playback and checkpointing active independently. [Guide to app architecture](https://developer.android.com/topic/architecture) · [UI layer](https://developer.android.com/topic/architecture/ui-layer)

- **Library** lists the bundled books and marks the book last played. Opening a book changes only the route.
- **Book** takes a book ID and joins the ordered catalog with reading progress. Unread rows have no progress indicator, partially read rows show the audible or saved word’s position within the chapter text, and read rows show a checkmark. The last-played icon is independent of status. Tapping a chapter selects it and opens Now Playing. Long-press actions send explicit mark-read or mark-unread commands.
- **Now Playing** follows the controller's selected chapter, including automatic transitions. It renders Play/Pause, Restart, Next and the two app-wide settings. For example, playing chapter 2 and then opening chapter 5 shows chapter 5 paused. Chapter 2 keeps its icon until another chapter actually plays.

On cold start, load the catalog, settings and reading state before choosing the initial route. Open Now Playing paused if the stored chapter exists, otherwise open Library. Restore the back stack and reconnect to the live selection when the activity is recreated in the same process. After process death, the cold-start rule takes precedence even if Android supplies saved navigation state. Recreating the UI must never issue a select, pause or play command as a side effect of drawing a screen.

## Playback and recovery

A user-initiated Play starts a foreground service of type `mediaPlayback` with a playback notification. Declare the foreground-service permissions for media playback. The service owns audio focus, a streaming `AudioTrack`, and the worker that pulls PCM from `MorsePcmStream`. Route UI commands, notification controls, focus loss and playback completion through one serialized controller. Whenever active playback pauses, persist the source offset of the audible word’s start and release audio resources when idle. Keep the paused selection available while the process lives. The choice of a higher-level player remains open under the existing project decision. [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types#media) · [Project decisions](../decisions.md)

Play enters the starting phase while the service acquires focus and prepares output. When the playback head first advances into this chapter’s frames, the controller enters playing and commits the chapter status, starting word and last-played IDs together. Apply the same rule to automatic continuation. If focus is denied or output initialization fails before that point, release the attempt’s resources and return to paused with the attempted chapter selected. Its prior status, resume position and the last-played record survive the failure.

Pause or a chapter switch during startup samples the playback head before flushing. If a frame already played, process the start transition before checkpointing. Otherwise cancel the attempt without a reading-state write. Ignore late events from the canceled attempt.

| Command | Transition |
| --- | --- |
| Play | Resume a partially read chapter at its saved word. Start an unread or read chapter at the first playable word. As audio starts, mark the chapter partially read and record it as last played. |
| Pause or select another chapter | Capture the audible word before flushing queued audio. Pause saves that position. Switching also selects the destination paused and preserves both chapters' statuses. Selecting the current chapter leaves the session as it is. |
| Restart | Return to the first playable word, update the saved position for a partially read chapter, and preserve playing or paused mode. |
| Mark read or unread | Persist the chosen status and clear the bookmark. Marking the playing chapter read pauses it and keeps it selected. Editing another chapter leaves playback running. |
| Next | Find the first later unread or partially read chapter in this book. Checkpoint the outgoing chapter and select the destination at its usual start position. Start it only if playback was active. Disable Next when there is no candidate. |
| Final audio frame heard | Mark the chapter read. Start the same eligible successor that Next would choose. If there is none, keep the finished chapter selected and paused. Its next Play starts at the first playable word. |

### From PCM frames to saved words

Target 10–15 seconds of rendered PCM ahead of playback in app memory. Feed AudioTrack through a shorter queue. The app owns the output queue and the played-frame count. If word 12 is audible while word 13 is queued, Pause saves word 12 and Resume begins at its first mark. The Book ViewModel computes the progress ratio on demand for display by dividing the audible or saved word’s source offset by the catalog’s chapter text length using floating-point division. Both values use UTF-16 code units, so the Book screen can draw the bar without opening the chapter text file.

Save each speed or tone change immediately. During playback, pause AudioTrack, sample the stopped playback head and persist the usual word-start checkpoint. Flush the track and discard the old rendered PCM. Rebuild the buffer from the saved word with the new settings, then resume automatically at its first mark. A brief gap is acceptable. If the listener pauses or selects another chapter during the rebuild, cancel the pending resume. Changing settings while already paused leaves the chapter paused at its existing position.

During playback, aim to persist the audible word at least once every ten seconds, including with the screen locked. Serialize periodic saves with pause, switch, restart, manual status edits and completion. Associate frame callbacks with their stream instance so a flushed track cannot overwrite a later position or complete a different chapter. After process loss, restore the last committed word and wait for Play.

### Core stream interface

Construct a `DefaultMorsePcmStream` with `StreamOptions` containing the full chapter text, sample rate and an optional UTF-16 text start offset. Pass speed and tone to each `read`. Core starts at the latest playable word beginning at or before the offset, using the first word if the offset precedes it. This also resolves bookmarks after chapter edits. For a settings change, open a fresh stream at the checkpoint and pass the updated options when reading. [Core API](../core/src/main/kotlin/com/github/danieladams456/morsebook/MorsePcmStream.kt)

The worker calls `read` with its existing PCM buffer. The returned `ReadResult` contains the frame count and a `WordStart` for each word whose first mark begins in this read. Source offsets stay relative to the original chapter across resumes. Frame offsets are relative to the first frame written by this call. The CLI uses `framesWritten` to write the PCM to its output file.

The worker adds the read’s starting frame to each reported frame offset. The latest start crossed by the playback head supplies the bookmark, even halfway through a read. A read can contain several word starts or continue a long word without introducing a new one.

For future highlighting, keep one word start of lookahead. Highlight the half-open source range `[currentStart, nextStart)`, including any intervening punctuation and whitespace. If the current word is the last reported in a PCM buffer, read ahead across as many buffers as needed to obtain the next start or confirm chapter EOF. At chapter EOF, use `chapterText.length` as the exclusive boundary. Only word starts need to cross the core interface.

## Implementation references and checks

In the inspected Now in Android checkout, `ForYouViewModel` uses `stateIn` to expose observable screen state. The outer `ForYouScreen` collects it with lifecycle awareness and passes callbacks to a content composable. Its user-data repository hides the preferences data source, and its ViewModel tests supply fake repositories. Those are direct models for Morsebook's Book screen, settings repository and screen tests. [ViewModel][nia-vm] · [Screen][nia-screen] · [Repository][nia-repo] · [ViewModel tests][nia-test]

Test chapter selection and status transitions on the JVM with a fake catalog, reading repository and audio output whose played-frame count advances independently of queued frames. Cover pause mid-word, skipped read chapters, stale completion after a switch, and finishing the last eligible chapter. With later words queued, change speed or tone halfway through a word. Verify that its start is saved before rebuilding and that the first audio after the gap replays that word from its first mark with the new settings. Exercise focus denial, output initialization failure and cancellation before the first frame. Check that a stale start callback cannot move the last-played icon. Use Android integration tests for Room transactions and routing. Play chapter 2, select chapter 5 without playing it, and recreate the activity. Expect chapter 5 paused. Repeat with process death and task restoration. Expect chapter 2 paused. On a device, verify that locking the screen preserves audio and checkpoints, then kill the process and check that reopening restores the saved word paused.

The planned Now Playing bar can observe the same controller. A future Gutenberg importer can add a catalog data source behind the existing repository. All MVP books ship with the app.

[nia-vm]: https://github.com/android/nowinandroid/blob/2982e63cb691ad49cd009aab43dc206fee6699c2/feature/foryou/impl/src/main/kotlin/com/google/samples/apps/nowinandroid/feature/foryou/impl/ForYouViewModel.kt
[nia-screen]: https://github.com/android/nowinandroid/blob/2982e63cb691ad49cd009aab43dc206fee6699c2/feature/foryou/impl/src/main/kotlin/com/google/samples/apps/nowinandroid/feature/foryou/impl/ForYouScreen.kt
[nia-repo]: https://github.com/android/nowinandroid/blob/2982e63cb691ad49cd009aab43dc206fee6699c2/core/data/src/main/kotlin/com/google/samples/apps/nowinandroid/core/data/repository/OfflineFirstUserDataRepository.kt
[nia-test]: https://github.com/android/nowinandroid/blob/2982e63cb691ad49cd009aab43dc206fee6699c2/feature/foryou/impl/src/test/kotlin/com/google/samples/apps/nowinandroid/feature/foryou/impl/ForYouViewModelTest.kt
