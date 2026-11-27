# App Screens

## MVP
This is explicitly single-device.  There is not a server-side sync mechanism.

### Screens
1. Library: shows the books stored on device. MVP will have 2–3 statically bundled books. Tapping a book opens its Book view.
2. Book: always shows the full chapter list in book order. Unread chapters have no progress indicator, partially read chapters have a progress bar, and read chapters have a checkmark. Opening the list leaves playback uninterrupted. Tapping a chapter opens Now Playing for that chapter.
3. Now Playing: shows the selected chapter title, Play/Pause, Previous/Next chapter, Morse speed, and tone frequency. Tapping Play starts or resumes the selected chapter. Playback continues when the user navigates away, switches apps, or locks the device.

### Playback Model
1. Progress tracks the current word regardless of playback speed. The position can skip unrendered characters as it advances to the next word. Pausing mid-word saves that word. Playback resumes from the beginning of the saved word, including after a fresh launch.
2. A chapter can be unread, partially read, or read. Starting an unread chapter or replaying a read chapter makes it partially read.
3. Long-press an unread or partially read chapter and choose “Mark as read” to mark it finished. Long-press a read chapter and choose “Mark as unread” to mark it unread again. Marking the playing chapter as read pauses playback and keeps that chapter selected. Changing the status of any other chapter leaves playback uninterrupted.
4. Selecting a different chapter from the list or with Previous/Next pauses any active playback, saves the previous chapter’s word if partially read, and leaves the newly selected chapter paused until the user taps Play. Both chapters keep their reading status. Opening the chapter already playing leaves playback uninterrupted.
5. A partially read chapter resumes at its saved word. An unread or read chapter starts at the beginning. This applies both to Play and to automatic continuation.
6. A fresh launch restores chapter statuses and saved words. The player session starts empty. To begin listening, the user selects a chapter and taps Play on Now Playing.
7. Listening to the end of a chapter marks it as read and automatically plays the next chapter at its usual starting position. At the end of the book, playback pauses with the final chapter selected. Tapping Play replays it from the beginning.

## Long Term Vision
1. A Now Playing bar at the bottom is persistent across all other screens except for the full-screen Now Playing view.  Tapping it navigates to the Now Playing screen.
2. Stretch goal: The Now Playing screen should show the text with current word highlighting
3. A content search screen allows searching for and downloading new books from Project Gutenberg
