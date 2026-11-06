package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R

/** The existing MediaViewer loading style, shared rather than a second indicator. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MediaLoadingIndicator() {
    androidx.compose.material3.ContainedLoadingIndicator()
}

/** Preserve the grid underneath; no status text or invented byte/percent progress. */
@Composable
fun MediaLoadingOverlay(onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))
        .clickable { /* Block underlying actions while loading. */ },
        contentAlignment = Alignment.Center) {
        MediaLoadingIndicator()
        IconButton(onClick = onCancel, modifier = Modifier.size(48.dp).align(Alignment.TopEnd)) {
            Icon(Icons.Default.Close, stringResource(R.string.button_cancel), tint = Color.White)
        }
    }
}
