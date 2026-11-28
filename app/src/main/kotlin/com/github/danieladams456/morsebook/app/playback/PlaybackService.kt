package com.github.danieladams456.morsebook.app.playback

import android.app.*
import com.github.danieladams456.morsebook.app.data.PlaybackSettings
import kotlinx.coroutines.runBlocking
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
    fun adjustSettings(wpmDelta: Int = 0, toneDelta: Int = 0)
}

class PlaybackRuntime(private val context: Context) : PlaybackControls {
    private val mutableState = MutableStateFlow(PlaybackState(historyLoaded = false))
    override val state: StateFlow<PlaybackState> = mutableState
    internal val media by lazy { PlaybackMedia(context, this) }
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val catalog = BundledCatalog(context.assets::open)
    private val settings = PlaybackSettings(context)
    private var tickSession = 0L
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
            }.copy(settings = runBlocking { settings.load() }))
        }
    }

    private fun focusLost(focus: android.media.AudioFocusRequest) {
        worker.execute { if (audio.owns(focus)) controller.pause() }
    }

    override fun select(chapter: ChapterKey) {
        if (state.value.selected != chapter) request.incrementAndGet()
        worker.execute { controller.select(chapter) }
    }

    fun nextRequest() = request.incrementAndGet()

    override fun requestPlay(chapter: ChapterKey) {
        context.startForegroundService(Intent(context, PlaybackService::class.java)
            .putExtra("request", nextRequest())
            .putExtra("book", chapter.book).putExtra("chapter", chapter.chapter))
    }

    override fun adjustSettings(wpmDelta: Int, toneDelta: Int) {
        worker.execute {
            val old = controller.state.settings
            val updated = old.copy(wpm = (old.wpm + wpmDelta).coerceIn(5, 100),
                toneHz = (old.toneHz + toneDelta).coerceIn(300, 1200))
            if (updated != old) {
                tickSession = controller.changeSettings(updated)
                runBlocking { settings.save(updated) }
            }
        }
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
            tickSession = controller.play()
            ticks = worker.scheduleWithFixedDelay({
                controller.tick(tickSession)
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

    private var latestStart = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStart = startId
        val state = playback.state.value
        val book = intent?.getStringExtra("book")
        val chapter = intent?.getStringExtra("chapter")
        val key = if (book != null && chapter != null) ChapterKey(book, chapter) else state.selected
        if (key == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startForeground(PlaybackMedia.ID, playback.media.notification(state.selected ?: key, state.phase))
        if (intent?.action == PlaybackMedia.PAUSE) {
            playback.pause()
            finishStart(startId)
        } else {
            val ticket = if (intent?.action == PlaybackMedia.PLAY) playback.nextRequest()
                else intent?.getLongExtra("request", -1) ?: -1
            playback.start(ticket, key) { mainHandler.post { finishStart(startId) } }
        }
        return START_NOT_STICKY
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun finishStart(startId: Int) {
        if (latestStart == startId) {
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf(startId)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
