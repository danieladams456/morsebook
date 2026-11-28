package com.github.danieladams456.morsebook.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.danieladams456.morsebook.app.data.Book
import com.github.danieladams456.morsebook.app.data.BundledCatalog

import com.github.danieladams456.morsebook.app.playback.*

class MainActivity : ComponentActivity() {
    private companion object {
        // Activity recreation keeps the route. A new process must initialize it from Room.
        val processId = java.util.UUID.randomUUID().toString()
        const val PROCESS_KEY = "morsebook.process"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(PROCESS_KEY, processId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState?.takeIf { it.getString(PROCESS_KEY) == processId })
        val books = BundledCatalog(assets::open).loadBooks()
        setContent { MaterialTheme { LibraryBrowser(books, (application as MorseApplication).playback) } }
    }
}

@Composable
internal fun LibraryBrowser(books: List<Book>, playback: PlaybackControls) {
    val playbackState by playback.state.collectAsState()
    if (!playbackState.historyLoaded) return
    var bookId by rememberSaveable { mutableStateOf(playbackState.selected?.book) }
    var chapterId by rememberSaveable { mutableStateOf(playbackState.selected?.chapter) }
    val book = books.find { it.id == bookId }
    val chapter = book?.chapters?.find { it.id == chapterId }
    val libraryScroll = rememberScrollState()
    val bookScroll = key(bookId) { rememberScrollState() }
    val back = { if (chapterId != null) chapterId = null else bookId = null }
    BackHandler(book != null, onBack = back)

    Scaffold { insets ->
        Column(Modifier.padding(insets).fillMaxSize().padding(horizontal = 20.dp)) {
            if (book != null) TextButton(onClick = back) { Text("Back") }
            Text(
                if (chapter != null) "Now Playing" else book?.title ?: "Library",
                modifier = Modifier.padding(vertical = 16.dp),
                style = MaterialTheme.typography.headlineMedium
            )
            when {
                chapter != null -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(chapter.title, style = MaterialTheme.typography.titleLarge)
                    val active = playbackState.phase != Phase.Paused
                    Button(onClick = {
                        if (active) playback.pause() else playback.requestPlay(ChapterKey(book.id, chapter.id))
                    }) { Text(if (active) "Pause" else "Play") }
                    val settings = playbackState.settings
                    SettingControl("CW speed", "${settings.wpm} WPM", settings.wpm > 5, settings.wpm < 100,
                        { playback.adjustSettings(wpmDelta = -1) }, { playback.adjustSettings(wpmDelta = 1) })
                    SettingControl("Tone", "${settings.toneHz} Hz", settings.toneHz > 300, settings.toneHz < 1200,
                        { playback.adjustSettings(toneDelta = -50) }, { playback.adjustSettings(toneDelta = 50) })
                }
                book != null -> Column(Modifier.verticalScroll(bookScroll)) {
                    book.chapters.forEach { item ->
                        val id = ChapterKey(book.id, item.id)
                        BrowseRow(
                            item.title, playbackState.lastPlayed == id,
                            playbackState.progress(id, item.textLength), id in playbackState.readChapters
                        ) {
                            playback.select(id)
                            chapterId = item.id
                        }
                    }
                }
                else -> Column(Modifier.verticalScroll(libraryScroll)) {
                    books.forEach { item -> BrowseRow(item.title, playbackState.lastPlayed?.book == item.id) { bookId = item.id } }
                }
            }
        }
    }
}

@Composable
private fun BrowseRow(title: String, lastPlayed: Boolean, progress: Float? = null, read: Boolean = false, open: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = when {
            read -> ({ Text("✓", Modifier.semantics { contentDescription = "Read" }) })
            progress != null -> ({ LinearProgressIndicator(progress = { progress }) })
            else -> null
        },
        trailingContent = if (lastPlayed) ({
            Text("▶", modifier = Modifier.semantics { contentDescription = "Last played" })
        }) else null,
        modifier = Modifier.fillMaxWidth().clickable(onClick = open)
    )
    HorizontalDivider()
}

@Composable
private fun SettingControl(label: String, value: String, canDecrease: Boolean, canIncrease: Boolean,
                           decrease: () -> Unit, increase: () -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("$label $value", Modifier.weight(1f))
        TextButton(decrease, Modifier.semantics { contentDescription = "Decrease $label" }, enabled = canDecrease) {
            Text("−")
        }
        TextButton(increase, Modifier.semantics { contentDescription = "Increase $label" }, enabled = canIncrease) {
            Text("+")
        }
    }
}
