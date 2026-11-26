# Code and Architecture
Use Kotlin targeting Java 17. Build with JDK 25, the current LTS release.
Use an immutable data class for all the synthesis options.  A factory should accept user-facing options like speed and frequency and return the full options data class also including derived options like ramp rate.

# App
The user should be able to change speed during a chapter.
Use the AudioTrack class as the base of the playback.  Defer higher-level player decisions until later.