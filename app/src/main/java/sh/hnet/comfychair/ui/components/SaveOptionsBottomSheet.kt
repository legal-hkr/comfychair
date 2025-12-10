package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R

/**
 * Bottom sheet for save/share options
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveOptionsBottomSheet(
    onDismiss: () -> Unit,
    onSaveToGallery: () -> Unit,
    onSaveAs: () -> Unit,
    onShare: () -> Unit,
    isVideo: Boolean = false
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            // Save to Gallery
            SaveOptionItem(
                icon = { Icon(Icons.Default.Save, contentDescription = null) },
                text = stringResource(R.string.save_to_gallery),
                onClick = onSaveToGallery
            )

            // Save As
            SaveOptionItem(
                icon = { Icon(Icons.Default.SaveAlt, contentDescription = null) },
                text = stringResource(R.string.save_as),
                onClick = onSaveAs
            )

            // Share
            SaveOptionItem(
                icon = { Icon(Icons.Default.Share, contentDescription = null) },
                text = stringResource(R.string.share),
                onClick = onShare
            )
        }
    }
}

@Composable
private fun SaveOptionItem(
    icon: @Composable () -> Unit,
    text: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
