# Morsebook Android MVP test plan

The most expensive playback bug is silently saving the wrong place. Drive the controller from tests that can advance the speaker’s playback head independently of the render queue. Use the device to establish that Android really advances that head, keeps the service alive behind the lock screen, and restores durable state after process loss. Use Maestro for the handful of promises a listener can verify through the shipped UI.

This plan covers the three MVP screens, playback, and on-device persistence. The persistent player bar, text highlighting, and Gutenberg downloads belong to later releases. The layers and cadence below adapt Android’s testing pyramid to a media app. [Functional requirements](functional-requirements.md) · [Architecture](architecture.md) · [Android testing strategies](https://developer.android.com/training/testing/fundamentals/strategies)

## Starting point

Gradle includes only `:core` and `:cli`. Both use Kotlin’s JUnit 4 integration, JDK 25 and Java 17 targets. Core already exercises mark/gap timing, buffer slices, option changes and positions across resumes. CLI tests cover rendering. The Python/NumPy validator independently measures generated WAV files and tests its ability to detect bad pitch, truncation and word gaps. Keep these checks as the synthesis reference. [Root build](../build.gradle.kts) · [Validator instructions](../validator/README.md)

The app directory currently holds specifications. There is no Android Gradle plugin (AGP), Android source set, DI or mocking framework, Robolectric, UI test library, screenshot suite, device runner, coverage setup or Maestro suite in the build. The design calls for Compose and constructor injection with an application-scoped container. Supply test implementations through those constructors and a replaceable debug/test container shared by the service and activity. The installed Android CLI exposes emulator, install, layout, input and screen commands. Maestro must be provisioned separately on developer and CI machines.

## When to write the test

| Implementation slice | Order | First piece of evidence |
| --- | --- | --- |
| Catalog validation, bookmark resolution, progress fraction and successor selection | **TDD**. Start with a small table of literal inputs and expected results. Implement one failing example at a time. | A read chapter between two eligible chapters is skipped. A later eligible chapter in another book is never returned. Use the same function for Next availability, manual Next and completion. |
| Playback controller, checkpoint scheduling and settings changes | **TDD**. Start with a failing command sequence against fakes. Add the smallest transition, rerun, then refactor while green. | Queue three words, advance the head into the second, pause, and assert that the second word’s source offset was saved before flush. Extend to cancellation and late callbacks before wiring up AudioTrack. |
| Repository contracts, ViewModels and navigation decisions | **TDD** for observable behavior. Run the reading contract against a fake first, then against Room on Android. | Playing chapter 2 and selecting 5 yields selected chapter 5 and last-played chapter 2. Test that distinction before implementing either icon or startup routing. |
| Compose interactions | Write the component behavior test alongside the first functional screen, before filling in the action handler. Build the visual layout first where the design is still changing. | Long-press a partial chapter, choose Mark as read, and verify the row exposes its new state. Record visual baselines once the layout has been reviewed. |
| AudioTrack, focus, foreground service, notification, Room/DataStore and lifecycle wiring | **Code first in a thin vertical slice**, followed immediately by integration tests on an emulator. Android must supply the actual behavior. Keep policy in the already-tested controller. | Deliver Play → advancing head → first durable bookmark on one chapter. Exercise real database reopen and real service lifecycle before expanding to the entire book. |
| Release flows and visual regressions | **Test second** when labels, navigation and rendered output exist. Write the acceptance expectations before implementation so the authoring agent has an independent oracle. | Run the same checked-in flow against debug and the installed release candidate. Make a deliberate local regression and confirm the relevant assertion fails. |

For a reported bug, first reproduce it at the lowest layer that can observe it, fix it there, and retain the regression case. A lock-screen-only failure needs a device reproducer even when the eventual fix is a few lines of Kotlin.

## Host tests and fixtures

Place Kotlin policy, repository contract and ViewModel tests in `app/src/test`. Use JUnit 4 and coroutine test dispatchers with virtual time. Construct a small ordered catalog with two books, mixed statuses, a partially read successor with a known bookmark, and a chapter long enough for multiple checkpoints. Include punctuation, unsupported characters and a supplementary Unicode character before a playable word. Specify expected UTF-16 offsets directly in the fixture. Validate every shipping asset for stable unique identifiers, chapter order and at least one playable word.

Give the controller a fake focus owner, audio output, clock, reading store and settings store. The output records frames submitted, frames played, session identity, pause and flush. Tests explicitly move the head and deliver events from old sessions. A recording repository exposes committed writes and their order. In a second set of feature tests, run the real core stream through the fake output so that conversion of read-relative frame offsets to chapter-relative playback positions is exercised.

| Case | Observable pass condition |
| --- | --- |
| Play and startup | Unread and read chapters start at their first playable word. Partial chapters resume at the saved word. Queuing audio leaves history untouched. The first played frame commits partially read status, starting offset and both last-played IDs together. Focus denial or output initialization failure releases resources, returns to paused and preserves prior history. |
| Startup race | Pause or switch just before the first played frame, then repeat just after it with the callback still pending. Only the second run records the start. A delayed start or completion from the old output cannot change the current selection, icons or history. |
| Position and periodic saves | Cover several word starts in one read, a word spanning reads, exact boundaries and later words already queued. Advance virtual playback across successive ten-second intervals and verify a committed audible-word offset each time. Interleave a pending periodic save with switch, restart, manual mark and completion. The newest transition survives. |
| Settings and interruptions | Change speed alone, tone alone, then both in succession mid-word. Verify each change persists, the head is sampled before flush, and the first PCM after rebuild matches a fresh core render from that word with the final settings. Pause or switch during rebuild cancels automatic resume. A paused edit preserves position and silence. Focus loss during play checkpoints and releases output. |
| Selection, restart and status | Reopening the playing chapter or browsing lists leaves playback running. Selecting another chapter pauses and checkpoints the outgoing one without starting the destination. Restart from playing and paused returns to the first word in the same mode. Manual mark-read/mark-unread clears the bookmark. Marking the playing chapter read pauses it and keeps it selected. Editing another chapter leaves output advancing. |
| Next and completion | Exercise Next in both modes, skipped read chapters and a bookmarked successor. Manual Next preserves the outgoing partial position. Only hearing the final frame completes a chapter. Auto-continuation marks it read and plays the eligible successor at its normal start. With none, Next is disabled, completion leaves the chapter selected and paused, and Play replays it. |
| Screen state and recovery | Missing reading rows display as unread. Partial progress uses floating-point division of the audible or saved word’s UTF-16 offset by the original string length. A shortened chapter clamps to the last playable word. Icons follow actual starts, including automatic starts, and survive pause. Missing or removed last-played ID routes to Library. A valid one opens Now Playing paused. |

## Compose behavior and appearance

Use Compose Testing APIs with Robolectric and a ComponentActivity in `app/src/test`. Feed immutable screen states into component tests, then connect real ViewModels to fake repositories for feature tests. Match accessible names, roles, selection, enabled state and progress semantics. Add a stable test tag where repeated chapter titles or icons would otherwise be ambiguous.

- **Library and Book** — assert bundled titles and full chapter order. Cover all three status indicators and the independent Now Playing icon on the same row. Exercise taps, long-press menu options and Back. Count controller commands to detect playback restarted by navigation or recomposition.
- **Now Playing** — assert chapter title, Play/Pause, Restart, Next availability, CW speed and tone callbacks. Verify auto-continuation changes the displayed chapter and settings appear consistently across chapters.
- **Recreation** — restore the Compose state and recreate the activity with the same controller. Verify the back stack, selection and saved scroll position, with no incidental Play, Pause or Select. Exercise compact and expanded windows and large fonts using DeviceConfigurationOverride.

Render each screen at widths 400, 610 and 900 dp crossed with heights 400, 500 and 1000 dp. At 400 × 500 dp add each supported theme and a 1.5× font variant. Include mixed chapter statuses, long titles, disabled Next and active/paused controls. Render reusable chapter rows and playback controls across themes and font scales. Pin the renderer, fonts and locale. Review expected/actual/diff images before accepting a changed baseline.

Choose the screenshot setup when the app pins AGP. With AGP 9.5.0-alpha03 or newer and a compatible engine, use the Compose Preview screenshot test suite. For a suite called screenshotTest on default/debug, the expected verification task is `:app:testScreenshotTestDefaultDebugTestSuite` and baselines live under `app/src/screenshotTestDefaultDebug/reference/`. For older supported AGP use the deprecated standalone plugin, `:app:validateDebugScreenshotTest` and `app/src/screenshotTestDebug/reference/`. Confirm generated task names with `./gradlew :app:tasks --all`. Reserve device screenshots for system surfaces such as the playback notification if a visual requirement arises. [Suite setup and task names](https://developer.android.com/studio/preview/compose-screenshot-testing-with-testsuites) · [Standalone setup](https://developer.android.com/studio/preview/compose-screenshot-testing)

## Android integration and recovery

Use AndroidJUnitRunner in `app/src/androidTest`. Run DAO and transaction cases with real in-memory Room on Android SQLite, including rollback of a failed start transaction. For recovery, close and reopen a file-backed database and Preferences DataStore in an isolated directory. Verify both settings, all three reading statuses, cleared and retained bookmarks, and last-played IDs. Check packaged backup rules cover both stores for cloud backup and device transfer on supported API levels.

Use a debug-only observer for head advancement, service state and committed bookmarks. Give each event a stream ID and monotonic timestamp. Keep process-loss orchestration in a separate host script or test package so killing the app leaves the test runner alive.

1. Play chapter 2, select chapter 5 without playing it, then recreate the activity. Expect chapter 5 paused. Kill the app process while preserving task and data, verify its PID changes on relaunch through Recents, and expect chapter 2 paused even if Android restores the old back stack. Both icons identify chapter 2.
2. With a long chapter playing, press Home, lock a dedicated device and observe at least two periodic commits as the real output head crosses known word starts. Check intervals against the ten-second playback target, recording scheduling delay separately. Capture the last committed offset, terminate the process without invoking Pause or clearing data, and reopen. Verify that offset, the paused state and replay from the saved word on Play. A clean stop alone misses this failure mode.
3. Exercise notification playback controls through UI Automator. Verify that notification and screen control the same session, focus loss checkpoints the audible word, and idle playback releases focus and track resources. Include a physical phone for release playback and screen-lock checks. Restore device lock and audio settings during teardown.

## Maestro and the AI authoring loop

Keep a small suite of plain YAML flows in `.maestro/`. Each starts from a known installation state and uses bundled content common to debug and release. Choose fixtures by stable visible titles or accessibility identifiers. For automatic continuation, choose a short shipping chapter and a bounded wait derived from its duration at the selected speed. Keep timing and exact bookmark assertions in the lower layers that can observe them.

| Flow | Assertions visible on the installed app |
| --- | --- |
| First listen and restore | Fresh install opens Library. Play a chapter, observe Pause, pause, then select a different chapter without playing it. Relaunch with data retained. The previously played title opens with Play available and the Library and Book icons identify it. |
| Manual reading status and Next | Mark an intervening chapter read. Play the preceding chapter, tap Next, and verify the eligible later title is playing. Return to Book, mark the intervening chapter unread, and verify its indicator and menu change. |
| Finish a book | Arrange the remaining later chapters as read. Play the final eligible short chapter, wait for Play to return, verify Next is disabled and the Book row is read. Replay and verify the row becomes partial. |

For each flow or regression, use this authoring cycle:

1. Give the agent the requirement, initial state and expected visible outcome. Have it draft the assertions before inspecting the app.
2. Install the intended APK on a disposable emulator. Use the Android CLI’s emulator, layout, input and screen commands to explore the real screen and discover stable selectors. Inspect subcommand help on the installed CLI for flags.
3. Have the agent write conventional Maestro actions and explicit assertions. Use state-based waits with timeouts. Every flow must run locally with AI credentials absent. Exclude Journeys, model-based visual assertions and generated actions at runtime.
4. Run the exact YAML with the local Maestro CLI. On failure, inspect the first failed step, hierarchy, screenshot and app log. Correct the selector, fixture or product defect indicated by the evidence. Preserve the requirement’s expected outcome. Repeat from clean state, and confirm the test detects a deliberately broken outcome during development.
5. Review and commit the YAML and fixture assumptions. CI replays those files with a pinned Maestro version and archives JUnit XML, failure screenshots and logs. The same cycle can produce Kotlin tests with deterministic assertions. [Maestro reports and artifacts](https://docs.maestro.dev/maestro-flows/workspace-management/test-reports-and-artifacts)

## Execution and rollout

Run commands from the repository root. Existing host checks require JDK 25 and the Python dependencies in `validator/requirements-dev.txt`.

| When | Command or gate |
| --- | --- |
| Today, on each change | `./gradlew check` runs core and CLI checks, Python validator tests and generated-audio acceptance. For a fast synthesis-only iteration use `./gradlew :core:test`. |
| Each app change, once configured | `./gradlew :app:testDebugUnitTest` runs controller, ViewModel and Robolectric tests. Add the selected screenshot verification task. Apply JaCoCo to tested modules and publish line/branch reports for changes to playback and recovery logic. Review uncovered transitions alongside the behavioral cases above. |
| Before merge | Run the host suite, preview diffs and `./gradlew :app:connectedDebugAndroidTest` on a pinned phone emulator. Include a debug Maestro smoke flow after `./gradlew :app:installDebug`. Use one connected device per job. |
| After merge or nightly | Run all debug Maestro flows and the external process-loss/lock driver. Rotate emulator jobs across the minimum supported API and the current target API once those are chosen. Archive API, APK revision, logs and timestamped checkpoint evidence for failures. |
| Release candidate | Install the actual signed, optimized candidate on a clean emulator and physical phone. Run `maestro test --format junit --output build/maestro.xml --test-output-dir build/maestro-results .maestro/` against that installed binary with app network access disabled. Repeat background/lock playback using public UI and system observations. Debug-only observers belong in the debug integration job. |

Implement in vertical slices. Start with the catalog, core adapter and Play/Pause controller; add durable bookmarks and cold start; then implement selection, settings and completion. With each slice, the implementer owns its host tests and adds the first integration assertion at the Android boundary it introduces. Add the matching Maestro flow when the interaction is usable. A release passes when the existing audio acceptance, MVP behavior cases, device recovery checks and candidate flows pass, and any intended visual changes have reviewed baselines.
