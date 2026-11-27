# App Screens

## MVP

### Screens
1. Library: shows the books stored on device. MVP will have 2–3 statically bundled books. A Now Playing icon marks the book containing the last chapter played. Tapping a book opens its Book view.
2. Book: always shows the full chapter list in book order. Unread chapters have no progress indicator, partially read chapters have a progress bar, and read chapters have a checkmark. A separate Now Playing icon marks the last chapter played. Both the book and chapter icons remain visible while paused. Opening the list leaves playback uninterrupted. Tapping a chapter opens Now Playing for that chapter.
3. Now Playing: shows the selected chapter title, Play/Pause, Restart chapter, Next chapter, and controls for CW speed and tone frequency. Both settings apply app-wide. Changing either setting during playback pauses audio, saves the start of the word being heard, rebuilds the buffer with the new settings, and automatically replays that word from the beginning. A brief delay is acceptable. Changing settings while paused leaves playback paused. Tapping Play starts or resumes the selected chapter. Playback continues when the user navigates away, switches apps, or locks the device.

### Playback Model
1. Progress tracks the word the listener is hearing. The Book screen computes the progress fraction for display from that word’s starting offset and the chapter text length. Playback ignores unsupported characters. If paused mid-word, playback resumes from the beginning of that word.
2. A chapter can be unread, partially read, or read. Starting an unread chapter or replaying a read chapter makes it partially read.
3. Long-press an unread or partially read chapter and choose “Mark as read” to mark it finished. Long-press a read chapter and choose “Mark as unread” to mark it unread again. Marking the playing chapter as read pauses playback and keeps that chapter selected. Changing the status of any other chapter leaves playback uninterrupted.
4. Selecting a different chapter from the list pauses any active playback and leaves the newly selected chapter paused until the user taps Play. Both chapters keep their reading status. Opening the chapter already playing leaves playback uninterrupted.
5. A partially read chapter resumes at the beginning of the word where playback stopped. An unread or read chapter starts at the beginning. Restart chapter moves playback to the first word of the selected chapter. If audio is playing, it plays from that word immediately. If paused, the chapter stays paused at the first word.
6. Next and automatic continuation both use the first later chapter in the same book that is unread or partially read. Tapping Next during playback starts the destination chapter immediately. Tapping it while paused leaves the destination paused. Finishing a chapter marks it as read and automatically plays the eligible chapter at its usual starting position. If no eligible chapter follows, Next is disabled and finishing the current chapter leaves it selected and paused. Tapping Play then replays it from the beginning.

### Persistence

All MVP state stays on the device.  There is no server-side system.

1. Save the user’s CW speed and tone frequency when either setting changes.
2. Save each chapter’s reading status whenever it changes, including manual changes. For a partially read chapter, persist only the word’s starting offset as the bookmark on every playback pause (including a settings change), chapter switch, or restart. During foreground and background playback, aim to save the current word at least once every 10 seconds of playback. After an unexpected stop, resume at the beginning of the most recently saved word.
3. Record the book and chapter when audio begins playing, including during automatic continuation. For example, playing chapter 2 and then opening chapter 5 leaves chapter 2 as the last chapter played.
4. On a cold start, restore playback settings, chapter statuses, and word positions. If a last played chapter exists, open Now Playing for that chapter and restore the icons for it and its book. Otherwise, open the Library. Audio stays paused until the listener taps Play.

## Long Term Vision
1. A Now Playing bar at the bottom is persistent across all other screens except for the full-screen Now Playing view.  Tapping it navigates to the Now Playing screen.
2. As a stretch goal, Now Playing highlights the chapter text from the audible word’s start up to the next playable word’s start, or to the end of the chapter for the final word.
3. A content search screen allows searching for and downloading new books from Project Gutenberg
