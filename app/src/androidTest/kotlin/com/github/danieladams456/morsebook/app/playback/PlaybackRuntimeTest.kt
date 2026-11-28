package com.github.danieladams456.morsebook.app.playback

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.danieladams456.morsebook.app.MainActivity
import com.github.danieladams456.morsebook.app.data.BundledCatalog
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackRuntimeTest {
    @Test fun restoredPlayStaleCommandsAndServiceOwnership() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val book = BundledCatalog(context.assets::open).loadBooks().first()
            val chapter = ChapterKey(book.id, book.chapters.first().id)
            var ticket = -1L
            val capture = object : ContextWrapper(context) {
                override fun startForegroundService(service: Intent): ComponentName? {
                    ticket = service.getLongExtra("request", -1)
                    return service.component
                }
            }
            val worker = Executors.newSingleThreadScheduledExecutor()
            val runtime = PlaybackRuntime(capture, worker)
            val old = PlaybackService()
            val current = PlaybackService()
            fun drain() { worker.submit {}.get(5, TimeUnit.SECONDS) }
            var stops = 0
            try {
                runtime.requestPlay(chapter)
                runtime.pause()
                runtime.start(ticket, chapter, old) { stops++ }
                drain()
                assertEquals(1, stops)
                assertEquals(Phase.Paused, runtime.state.value.phase)

                val missing = ChapterKey(book.id, "missing")
                runtime.requestPlay(missing)
                runtime.start(ticket, missing, old) { stops++ }
                drain()
                assertEquals(2, stops)
                assertEquals(StartupError.PlaybackFailed, runtime.state.value.startupError)

                runtime.requestPlay(chapter)
                runtime.start(ticket, chapter, old) { stops++ }
                drain()
                assertEquals(chapter, runtime.state.value.selected)
                assertNotEquals(Phase.Paused, runtime.state.value.phase)
                runtime.requestPlay(chapter)
                runtime.start(ticket, chapter, current) { stops++ }
                runtime.destroyed(old)
                drain()
                assertNotEquals(Phase.Paused, runtime.state.value.phase)
                assertNull(runtime.state.value.startupError)
                runtime.destroyed(current)
                drain()
                assertEquals(Phase.Paused, runtime.state.value.phase)
            } finally {
                runtime.destroyed(old)
                runtime.destroyed(current)
                drain()
                worker.shutdownNow()
            }
        }
    }
}
