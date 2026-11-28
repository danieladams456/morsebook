package com.github.danieladams456.morsebook.app.playback

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.github.danieladams456.morsebook.app.data.BundledCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class MorseApplication : Application() {
    val playback by lazy { PlaybackRuntime(this) }
}

class PlaybackRuntime(
    private val context: Context,
    private val worker: java.util.concurrent.ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
) {
    val state = MutableStateFlow(PlaybackState())
    private val catalog = BundledCatalog(context.assets::open)
    private val audio = AndroidAudio(context, ::focusLost)
    private val controller = PlaybackController({ catalog.loadChapterText(it.book, it.chapter) }, audio) {
        state.value = it
    }
    private var ticks: ScheduledFuture<*>? = null
    private var activeOwner: PlaybackService? = null
    private val request = AtomicLong()

    private fun focusLost(focus: android.media.AudioFocusRequest) {
        worker.execute { if (audio.owns(focus)) controller.pause() }
    }

    fun select(chapter: ChapterKey) {
        request.incrementAndGet()
        worker.execute { controller.select(chapter) }
    }

    fun requestPlay(chapter: ChapterKey) {
        val ticket = request.incrementAndGet()
        worker.execute { controller.select(chapter) }
        context.startForegroundService(Intent(context, PlaybackService::class.java)
            .putExtra("request", ticket)
            .putExtra("book", chapter.book).putExtra("chapter", chapter.chapter))
    }

    fun pause() {
        request.incrementAndGet()
        worker.execute { controller.pause() }
    }

    fun start(ticket: Long, chapter: ChapterKey, owner: PlaybackService, stopped: () -> Unit) {
        worker.execute {
            if (request.get() != ticket || controller.state.selected != chapter) {
                stopped()
                return@execute
            }
            ticks?.cancel(false)
            val session = controller.play()
            if (controller.state.phase == Phase.Paused) {
                activeOwner = null
                stopped()
                return@execute
            }
            activeOwner = owner
            ticks = worker.scheduleWithFixedDelay({
                controller.tick(session)
                if (controller.state.phase == Phase.Paused) {
                    ticks?.cancel(false)
                    activeOwner = null
                    stopped()
                }
            }, 0, 10, TimeUnit.MILLISECONDS)
        }
    }

    fun destroyed(owner: PlaybackService) {
        worker.execute {
            if (activeOwner === owner) {
                activeOwner = null
                ticks?.cancel(false)
                controller.pause()
            }
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
        else playback.start(intent.getLongExtra("request", -1), ChapterKey(book, chapter), this) { stopSelf(startId) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        playback.destroyed(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
