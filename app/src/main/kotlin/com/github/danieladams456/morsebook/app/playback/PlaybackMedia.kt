package com.github.danieladams456.morsebook.app.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState as SystemState
import com.github.danieladams456.morsebook.app.MainActivity
import com.github.danieladams456.morsebook.app.data.BundledCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The system card follows the same process-scoped selection as Now Playing. */
internal class PlaybackMedia(private val context: Context, private val controls: PlaybackControls) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val books = BundledCatalog(context.assets::open).loadBooks()
    private val activity = PendingIntent.getActivity(context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE)
    private val session = MediaSession(context, "MorseBook").apply {
        setSessionActivity(activity)
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() { controls.state.value.selected?.let(controls::requestPlay) }
            override fun onPause() = controls.pause()
        })
        isActive = true
    }

    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW))
        CoroutineScope(Dispatchers.Main).launch {
            controls.state.map { it.selected to it.phase }.distinctUntilChanged().collect { (key, phase) ->
                if (key != null) post(key, phase)
            }
        }
    }

    // MediaSession notifications are exempt from Android 13 notification permission.
    @android.annotation.SuppressLint("NotificationPermission")
    private fun post(key: ChapterKey, phase: Phase) = manager.notify(ID, notification(key, phase))

    fun notification(key: ChapterKey, phase: Phase): Notification {
        val book = books.first { it.id == key.book }
        val title = book.chapters.first { it.id == key.chapter }.title
        val active = phase != Phase.Paused
        val icon = if (active) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val label = if (active) "Pause" else "Play"
        val intent = Intent(context, PlaybackService::class.java).setAction(if (active) PAUSE else PLAY)
        val action = if (active) PendingIntent.getService(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            else PendingIntent.getForegroundService(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, book.title).build())
        session.setPlaybackState(SystemState.Builder()
            .setActions(SystemState.ACTION_PLAY or SystemState.ACTION_PAUSE or SystemState.ACTION_PLAY_PAUSE)
            .setState(if (active) SystemState.STATE_PLAYING else SystemState.STATE_PAUSED,
                SystemState.PLAYBACK_POSITION_UNKNOWN, if (active) 1f else 0f).build())
        return Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title).setContentText(book.title).setContentIntent(activity)
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0))
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(context, icon), label, action).build())
            .setOnlyAlertOnce(true).setOngoing(active).build()
    }

    companion object {
        const val ID = 1
        const val PLAY = "morsebook.play"
        const val PAUSE = "morsebook.pause"
        private const val CHANNEL = "playback"
    }
}
