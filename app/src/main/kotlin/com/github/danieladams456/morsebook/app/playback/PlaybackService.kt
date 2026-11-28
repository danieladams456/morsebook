package com.github.danieladams456.morsebook.app.playback

import android.app.*
import android.util.Log
import androidx.room.Room
import com.github.danieladams456.morsebook.app.BuildConfig
import com.github.danieladams456.morsebook.app.data.restoreReading
import com.github.danieladams456.morsebook.app.data.ReadingDatabase
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.github.danieladams456.morsebook.app.data.BundledCatalog
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class MorseApplication : Application() {
    val playback by lazy { PlaybackRuntime(this) }
}

interface PlaybackControls {
    val state: StateFlow<PlaybackState>
    fun select(chapter: ChapterKey)
    fun requestPlay(chapter: ChapterKey)
    fun pause()
}

class PlaybackRuntime(private val context: Context) : PlaybackControls {
    private val mutableState = MutableStateFlow(PlaybackState(historyLoaded = false))
    override val state: StateFlow<PlaybackState> = mutableState
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val catalog = BundledCatalog(context.assets::open)
    private val audio = AndroidAudio(context, ::focusLost)
    private val database = Room.databaseBuilder(context, ReadingDatabase::class.java, "reading.db").build()
    private val reading = database.reading()
    private val controller = PlaybackController({ catalog.loadChapterText(it.book, it.chapter) }, audio, reading,
        onCommit = { kind, session, head, offset ->
            if (BuildConfig.DEBUG) Log.d("MorseCheckpoint", "$kind session=$session head=$head offset=$offset")
        }) {
        mutableState.value = it
    }
    private var ticks: ScheduledFuture<*>? = null
    private val request = AtomicLong()

    init {
        worker.execute {
            controller.restore(restoreReading(catalog.loadBooks(), reading.chapters(), reading.lastPlayed()) {
                catalog.loadChapterText(it.book, it.chapter)
            })
        }
    }

    private fun focusLost(focus: android.media.AudioFocusRequest) {
        worker.execute { if (audio.owns(focus)) controller.pause() }
    }

    override fun select(chapter: ChapterKey) {
        if (state.value.selected != chapter) request.incrementAndGet()
        worker.execute { controller.select(chapter) }
    }

    override fun requestPlay(chapter: ChapterKey) {
        context.startForegroundService(Intent(context, PlaybackService::class.java)
            .putExtra("request", request.incrementAndGet())
            .putExtra("book", chapter.book).putExtra("chapter", chapter.chapter))
    }

    override fun pause() {
        request.incrementAndGet()
        worker.execute { controller.pause() }
    }

    fun start(ticket: Long, chapter: ChapterKey, stopped: () -> Unit) {
        worker.execute {
            if (request.get() != ticket || controller.state.selected != chapter) {
                stopped()
                return@execute
            }
            ticks?.cancel(false)
            val session = controller.play()
            ticks = worker.scheduleWithFixedDelay({
                controller.tick(session)
                if (controller.state.phase == Phase.Paused) {
                    ticks?.cancel(false)
                    stopped()
                }
            }, 0, 10, TimeUnit.MILLISECONDS)
        }
    }
}

class PlaybackService : Service() {
    private val playback get() = (application as MorseApplication).playback

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channel = "playback"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "Playback", NotificationManager.IMPORTANCE_LOW))
        val notice = Notification.Builder(this, channel).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("MorseBook").setContentText("Morse playback").setOngoing(true).build()
        startForeground(1, notice)
        val book = intent?.getStringExtra("book")
        val chapter = intent?.getStringExtra("chapter")
        if (book == null || chapter == null) stopSelf(startId)
        else playback.start(intent.getLongExtra("request", -1), ChapterKey(book, chapter)) { stopSelf(startId) }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
