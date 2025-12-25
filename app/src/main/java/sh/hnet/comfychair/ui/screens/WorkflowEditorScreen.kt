package sh.hnet.comfychair.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.HighlightOff
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.WorkflowGraphCanvas
import sh.hnet.comfychair.viewmodel.WorkflowPreviewerViewModel
import sh.hnet.comfychair.workflow.FieldMappingState
import sh.hnet.comfychair.workflow.WorkflowMappingState

/**
 * Main screen for the workflow previewer
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowPreviewerScreen(
    viewModel: WorkflowPreviewerViewModel,
    onClose: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val density = LocalDensity.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.workflowName.ifEmpty { stringResource(R.string.workflow_previewer_title) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (uiState.isFieldMappingMode) {
                            viewModel.cancelMapping()
                        } else {
                            onClose()
                        }
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.content_description_back)
                        )
                    }
                },
                actions = {
                    if (uiState.isFieldMappingMode) {
                        IconButton(onClick = { viewModel.cancelMapping() }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.button_cancel)
                            )
                        }
                        IconButton(
                            onClick = { viewModel.confirmMapping() },
                            enabled = uiState.canConfirmMapping
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = stringResource(R.string.button_confirm),
                                tint = if (uiState.canConfirmMapping)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                uiState.errorMessage != null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.workflow_previewer_error_loading),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = uiState.errorMessage ?: "",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                uiState.graph != null -> {
                    // Graph canvas
                    WorkflowGraphCanvas(
                        graph = uiState.graph!!,
                        scale = uiState.scale,
                        offset = uiState.offset,
                        showTemplateHighlight = uiState.showTemplateHighlight && !uiState.isFieldMappingMode,
                        isFieldMappingMode = uiState.isFieldMappingMode,
                        highlightedNodeIds = uiState.highlightedNodeIds,
                        mappingState = uiState.mappingState,
                        selectedFieldKey = uiState.selectedFieldKey,
                        onNodeTapped = if (uiState.isFieldMappingMode) { nodeId ->
                            viewModel.onNodeTapped(nodeId)
                        } else null,
                        onTransform = { scale, offset ->
                            viewModel.onTransform(scale, offset)
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { size ->
                                with(density) {
                                    viewModel.setCanvasSize(
                                        size.width.toFloat(),
                                        size.height.toFloat()
                                    )
                                }
                            }
                    )
                }
            }

            // Field mapping panel (only in mapping mode)
            if (uiState.isFieldMappingMode && uiState.mappingState != null) {
                FieldMappingPanel(
                    mappingState = uiState.mappingState!!,
                    selectedFieldKey = uiState.selectedFieldKey,
                    onFieldSelected = { viewModel.selectFieldForMapping(it) },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp, bottom = 88.dp, end = 16.dp)
                )
            }

            // Bottom controls
            AnimatedVisibility(
                visible = uiState.graph != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                if (uiState.isFieldMappingMode) {
                    MappingBottomControls(
                        scale = uiState.scale,
                        onZoomIn = { viewModel.zoomIn() },
                        onZoomOut = { viewModel.zoomOut() },
                        onResetZoom = { viewModel.resetView() }
                    )
                } else {
                    BottomControls(
                        scale = uiState.scale,
                        showHighlight = uiState.showTemplateHighlight,
                        onZoomIn = { viewModel.zoomIn() },
                        onZoomOut = { viewModel.zoomOut() },
                        onResetZoom = { viewModel.resetView() },
                        onToggleHighlight = { viewModel.toggleTemplateHighlight() }
                    )
                }
            }
        }
    }
}

@Composable
private fun FieldMappingPanel(
    mappingState: WorkflowMappingState,
    selectedFieldKey: String?,
    onFieldSelected: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.field_mapping_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.heightIn(max = 180.dp)) {
                items(
                    items = mappingState.fieldMappings,
                    key = { "${it.field.fieldKey}_${it.selectedCandidateIndex}" }
                ) { fieldMapping ->
                    FieldMappingRow(
                        fieldMapping = fieldMapping,
                        isSelected = fieldMapping.field.fieldKey == selectedFieldKey,
                        onSelect = {
                            val newKey = if (fieldMapping.field.fieldKey == selectedFieldKey) null else fieldMapping.field.fieldKey
                            onFieldSelected(newKey)
                        }
                    )
                }
            }

            if (mappingState.fieldMappings.any { it.hasMultipleCandidates }) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.tap_node_to_change),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun FieldMappingRow(
    fieldMapping: FieldMappingState,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    } else {
        Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fieldMapping.field.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )

            when {
                fieldMapping.isMapped -> {
                    val selectedCandidate = fieldMapping.selectedCandidate
                    Text(
                        text = "${selectedCandidate?.nodeName} (${selectedCandidate?.classType})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                fieldMapping.needsRemapping -> {
                    Text(
                        text = stringResource(R.string.needs_remapping),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                else -> {
                    Text(
                        text = stringResource(R.string.no_matching_nodes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        if (fieldMapping.isMapped) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun BottomControls(
    scale: Float,
    showHighlight: Boolean,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onResetZoom: () -> Unit,
    onToggleHighlight: () -> Unit
) {
    val highlightColor = MaterialTheme.colorScheme.tertiary

    Surface(
        modifier = Modifier
            .padding(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Zoom out button
            FilledIconButton(
                onClick = onZoomOut,
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(
                    Icons.Default.Remove,
                    contentDescription = stringResource(R.string.workflow_previewer_zoom_out)
                )
            }

            // Zoom percentage
            Text(
                text = "${(scale * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            // Zoom in button
            FilledIconButton(
                onClick = onZoomIn,
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.workflow_previewer_zoom_in)
                )
            }

            // Fit to screen button
            FilledIconButton(
                onClick = onResetZoom,
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(
                    Icons.Default.FitScreen,
                    contentDescription = stringResource(R.string.workflow_previewer_reset_zoom)
                )
            }

            // Template highlight toggle
            FilledIconButton(
                onClick = onToggleHighlight,
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (showHighlight) highlightColor.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = if (showHighlight) highlightColor else MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(
                    if (showHighlight) Icons.Default.Highlight else Icons.Default.HighlightOff,
                    contentDescription = stringResource(R.string.workflow_previewer_highlight_templates)
                )
            }
        }
    }
}

@Composable
private fun MappingBottomControls(
    scale: Float,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onResetZoom: () -> Unit
) {
    Surface(
        modifier = Modifier.padding(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Zoom controls
            FilledIconButton(
                onClick = onZoomOut,
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(Icons.Default.Remove, contentDescription = null)
            }

            Text(
                text = "${(scale * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium
            )

            FilledIconButton(
                onClick = onZoomIn,
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
            }

            FilledIconButton(
                onClick = onResetZoom,
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(Icons.Default.FitScreen, contentDescription = null)
            }
        }
    }
}
