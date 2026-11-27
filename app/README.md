# App Screens

## MVP
This is explicitly single-device.  There is not a server-side sync mechanism.

### Screens
1. Library: view all books currently in your library.  "Currently in your library" means stored on device.  We can handle that since the text files are so small.  MVP will have 2-3 statically bundled books.
2. Book: shows chapters and listening progress.  A chapter should have a checkmark for completion and a progress bar for partial.  Tapping the currently playing chapter on the Book screen reopens Now Playing without interrupting playback.
3. Now playing screen: full screen view of currently playing chapter. No chapter text, just title and media controls.  Media controls include play/pause, previous/next chapter, morse code speed and tone Hz.  The currently playing chapter should continue playing when the user navigates to another screen or leaves the app.  This will set up well for the persistent Now Playing bar in the future.

### Playback Model
1. Progress is based on text position so it is irrespective of speed, which can be changed.
2. A chapter can be unread, partially read, or read.  Starting an unread chapter or replaying a read chapter makes it partially read.
3. Long-press an unread or partially read chapter and choose “Mark as read” to mark it finished.  Long-press a read chapter and choose “Mark as unread” to mark it unread again.
4. A partially read chapter resumes where you left off. An unread or read chapter starts at the beginning. This applies when you select a chapter, use Previous or Next, or the player advances automatically.
5. Persistence: When you reopen the app, each chapter keeps its reading status and each partially read chapter remembers where you left off.
6. Continuation: Listening to the end of a chapter marks it as read and continues to the next chapter if one exists.  It stops at the end of the book.
7. Skipping to the next chapter does NOT mark the current chapter as fully read.  Progress remains at the point where the listener stopped.

## Long Term Vision
1. A Now Playing bar at the bottom is persistent across all other screens except for the full-screen Now Playing view.  Tapping it navigates to the Now Playing screen.
2. Stretch goal: The Now Playing screen should show the text with current word highlighting
3. A content search screen allows searching for and downloading new books from Project Gutenberg
