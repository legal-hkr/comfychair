package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import sh.hnet.comfychair.ui.components.shared.NoOverscrollContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.hnet.comfychair.R
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.LogEntry
import sh.hnet.comfychair.util.LogLevel

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
            val hadEntries = entries.size
            entries.clear()
            entries.addAll(newEntries)

            // Scroll to bottom if following and we have new entries
            if (isFollowing && entries.isNotEmpty() && entries.size > hadEntries) {
                listState.scrollToItem(entries.size - 1)
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.8f),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Header
                TopAppBar(
                    title = { Text(stringResource(R.string.debug_log_title)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.content_description_close)
                            )
                        }
                    },
                    actions = {
                        // Follow/scroll-to-bottom button
                        IconButton(
                            onClick = {
                                scope.launch {
                                    isFollowing = true
                                    if (entries.isNotEmpty()) {
                                        listState.scrollToItem(entries.size - 1)
                                    }
                                }
                            },
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = if (isFollowing)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Icon(
                                Icons.Default.VerticalAlignBottom,
                                contentDescription = stringResource(R.string.debug_log_follow)
                            )
                        }
                        IconButton(onClick = onSave) {
                            Icon(
                                Icons.Default.Save,
                                contentDescription = stringResource(R.string.debug_log_save)
                            )
                        }
                    },
                    windowInsets = WindowInsets(0, 0, 0, 0)
                )

                // Log entries
                if (entries.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.debug_log_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    NoOverscrollContainer(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp)
                        ) {
                            itemsIndexed(
                                items = entries,
                                key = { index, entry -> "${index}_${entry.timestamp}" }
                            ) { _, entry ->
                                LogEntryItem(entry)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogEntryItem(entry: LogEntry) {
    val color = when (entry.level) {
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
        LogLevel.WARN -> Color(0xFFFF9800)  // Orange
        LogLevel.INFO -> MaterialTheme.colorScheme.primary
        LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Text(
        text = entry.format(),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = color,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}
