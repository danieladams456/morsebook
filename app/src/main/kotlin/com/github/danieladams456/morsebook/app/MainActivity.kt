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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.danieladams456.morsebook.app.data.Book
import com.github.danieladams456.morsebook.app.data.BundledCatalog

import com.github.danieladams456.morsebook.app.playback.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val books = BundledCatalog(assets::open).loadBooks()
        setContent { MaterialTheme { LibraryBrowser(books, (application as MorseApplication).playback) } }
    }
}

@Composable
private fun LibraryBrowser(books: List<Book>, playback: PlaybackRuntime) {
    val playbackState by playback.state.collectAsState()
    var bookId by rememberSaveable { mutableStateOf<String?>(null) }
    var chapterId by rememberSaveable { mutableStateOf<String?>(null) }
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
                chapter != null -> {
                    Text(chapter.title, style = MaterialTheme.typography.titleLarge)
                    val active = playbackState.phase != Phase.Paused
                    Button(onClick = {
                        if (active) playback.pause() else playback.requestPlay(ChapterKey(book.id, chapter.id))
                    }) { Text(if (active) "Pause" else "Play") }
                }
                book != null -> Column(Modifier.verticalScroll(bookScroll)) {
                    book.chapters.forEach { item ->
                        BrowseRow(item.title) {
                            playback.select(ChapterKey(book.id, item.id))
                            chapterId = item.id
                        }
                    }
                }
                else -> Column(Modifier.verticalScroll(libraryScroll)) {
                    books.forEach { item -> BrowseRow(item.title) { bookId = item.id } }
                }
            }
        }
    }
}

@Composable
private fun BrowseRow(title: String, open: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        modifier = Modifier.fillMaxWidth().clickable(onClick = open)
    )
    HorizontalDivider()
}
