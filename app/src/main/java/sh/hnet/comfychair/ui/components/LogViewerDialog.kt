package sh.hnet.comfychair.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.shared.NoOverscrollContainer
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.LogEntry
import sh.hnet.comfychair.util.LogLevel
import sh.hnet.comfychair.util.PrivacyUtils

/**
 * A fullscreen dialog for viewing debug logs.
 * Shows log entries with color-coding by level.
 * Auto-refreshes and auto-scrolls to follow new entries.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerDialog(
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val entries = remember { mutableStateListOf<LogEntry>() }
    val listState = rememberLazyListState()
    var isFollowing by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    // Check if we're at the bottom of the list
    val isAtBottom by remember {
        derivedStateOf {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            totalItems == 0 || lastVisibleItem >= totalItems - 2
        }
    }

    // Update isFollowing when user scrolls to bottom manually
    LaunchedEffect(isAtBottom) {
        if (isAtBottom) {
            isFollowing = true
        }
    }

    // Auto-refresh every 500ms and scroll if following
    LaunchedEffect(Unit) {
        while (true) {
            val newEntries = DebugLogger.getEntries()
            // Compare the last entry, not the size: a full ring buffer keeps its size while
            // new entries keep arriving
            if (newEntries.size != entries.size || newEntries.lastOrNull() != entries.lastOrNull()) {
                entries.clear()
                entries.addAll(newEntries)

                // Scroll to bottom if following
                if (isFollowing && entries.isNotEmpty()) {
                    listState.scrollToItem(entries.size - 1)
                }
            }

            delay(500)
        }
    }

    // Detect user scrolling up (away from bottom)
    LaunchedEffect(listState.firstVisibleItemIndex) {
        if (!isAtBottom && entries.isNotEmpty()) {
            isFollowing = false
        }
    }

    val context = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.content_description_close))
                    }
                    Text(
                        stringResource(R.string.title_debug_log),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (entries.isEmpty()) {
                        Text(
                            text = stringResource(R.string.msg_debug_log_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        NoOverscrollContainer(modifier = Modifier.fillMaxSize()) {
                            SelectionContainer {
                                LazyColumn(
                                    state = listState,
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    itemsIndexed(entries, key = { index, entry -> "${index}_${entry.timestamp}" }) { _, entry ->
                                        LogEntryItem(entry)
                                    }
                                }
                            }
                        }
                    }
                    // New lines follow by themselves; scrolled up, a way back to the end
                    if (!isFollowing && entries.isNotEmpty()) {
                        SmallFloatingActionButton(
                            onClick = {
                                scope.launch {
                                    isFollowing = true
                                    listState.scrollToItem(entries.size - 1)
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp)
                        ) {
                            Icon(Icons.Rounded.VerticalAlignBottom, contentDescription = stringResource(R.string.debug_log_follow))
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    TextButton(
                        onClick = {
                            PrivacyUtils.copySensitiveText(context, "log", DebugLogger.exportToString())
                            Toast.makeText(context, R.string.msg_copied_to_clipboard, Toast.LENGTH_SHORT).show()
                        },
                        enabled = entries.isNotEmpty()
                    ) { Text(stringResource(R.string.button_copy)) }
                    Button(
                        onClick = onSave,
                        enabled = entries.isNotEmpty(),
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                    ) { Text(stringResource(R.string.debug_log_save)) }
                }
            }
        }
    }
}

@Composable
private fun LogEntryItem(entry: LogEntry) {
    val color = when (entry.level) {
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
        LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
        LogLevel.INFO, LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Text(
        text = entry.format(),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = color,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}
