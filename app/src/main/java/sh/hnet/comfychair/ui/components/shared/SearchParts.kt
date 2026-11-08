package sh.hnet.comfychair.ui.components.shared

import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R

/*
 * The app's search pattern (spec §22.5): the field on the page, the filters under it (at most two rows, the rest in a
 * menu), a count line while a query or filter is on, then the list (the page's own structure while the field is empty,
 * one flat list of matches while it isn't), and an empty state that can lift the filters.
 */

/**
 * The search field on the page: Material 3's search input in its state-based form, standing on its own (typing happens
 * in place, no search bar expands; a search bar would also take no typing while collapsed), given the search bar's
 * container colour through its colours. The keyboard's Search action hides it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchField(state: TextFieldState, placeholder: String, modifier: Modifier = Modifier) {
    // The input expects a search bar's state; nothing opens from it here
    val searchBarState = rememberSearchBarState()
    val keyboard = LocalSoftwareKeyboardController.current
    SearchBarDefaults.InputField(
        textFieldState = state,
        searchBarState = searchBarState,
        onSearch = { keyboard?.hide() },
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = if (state.text.isNotEmpty()) {
            {
                IconButton(onClick = { state.clearText() }) {
                    Icon(Icons.Rounded.Clear, contentDescription = stringResource(R.string.button_clear_search))
                }
            }
        } else null,
        // A search bar's container (its own colour role), which the input leaves to the bar it usually sits in
        colors = SearchBarDefaults.inputFieldColors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        modifier = modifier.fillMaxWidth()
    )
}

/** A filter chip: [leading] shown when not selected (selected chips show the check). */
class SearchFilter(
    val key: String,
    val label: String,
    val selected: Boolean,
    val onToggle: () -> Unit,
    val leading: (@Composable () -> Unit)? = null
)

/**
 * The filters under the field as chips wrapping into at most [maxRows] rows; what doesn't fit goes into a last chip
 * ([moreLabel] with how many) opening a menu of the rest, each checkable. The chip shows selected while one of them is.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SearchFilterChips(
    filters: List<SearchFilter>,
    moreLabel: (Int) -> String,
    modifier: Modifier = Modifier,
    maxRows: Int = 2
) {
    val gap = 8.dp
    SubcomposeLayout(modifier.fillMaxWidth()) { constraints ->
        val gapPx = gap.roundToPx()
        val loose = Constraints(maxWidth = constraints.maxWidth)
        val chips = filters.mapIndexed { index, filter ->
            subcompose("chip_$index") { FilterChipOf(filter) }.first().measure(loose)
        }
        // The rows the first [count] chips take, with [extra] (the more chip's width) after them
        fun rowsFor(count: Int, extra: Int?): Int {
            var rows = 1
            var x = 0
            val widths = chips.take(count).map { it.width } + listOfNotNull(extra)
            widths.forEach { width ->
                if (x > 0 && x + gapPx + width > constraints.maxWidth) {
                    rows++
                    x = width
                } else {
                    x += (if (x > 0) gapPx else 0) + width
                }
            }
            return rows
        }
        var shown = chips.size
        var more: androidx.compose.ui.layout.Placeable? = null
        if (rowsFor(chips.size, null) > maxRows) {
            // The widest the more chip gets (its count is at most all of them)
            val probe = subcompose("probe") { MoreChip(moreLabel(filters.size), hidden = emptyList()) }.first().measure(loose)
            shown = (chips.size - 1 downTo 0).first { rowsFor(it, probe.width) <= maxRows }
            more = subcompose("more") { MoreChip(moreLabel(filters.size - shown), hidden = filters.drop(shown)) }.first().measure(loose)
        }
        val placed = chips.take(shown) + listOfNotNull(more)
        val positions = mutableListOf<Pair<Int, Int>>()
        var x = 0
        var y = 0
        var rowHeight = 0
        placed.forEach { chip ->
            if (x > 0 && x + gapPx + chip.width > constraints.maxWidth) {
                x = 0
                y += rowHeight
                rowHeight = 0
            }
            if (x > 0) x += gapPx
            positions += x to y
            x += chip.width
            rowHeight = maxOf(rowHeight, chip.height)
        }
        layout(constraints.maxWidth, if (placed.isEmpty()) 0 else y + rowHeight) {
            placed.forEachIndexed { i, chip -> chip.place(positions[i].first, positions[i].second) }
        }
    }
}

@Composable
private fun FilterChipOf(filter: SearchFilter) {
    FilterChip(
        selected = filter.selected,
        onClick = filter.onToggle,
        label = { Text(filter.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = if (filter.selected) {
            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else filter.leading
    )
}

/** The chip for the filters that didn't fit: their menu, each item toggling its filter (checked ones marked). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MoreChip(label: String, hidden: List<SearchFilter>) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = hidden.any { it.selected },
            onClick = { open = true },
            label = { Text(label, maxLines = 1) },
            trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        )
        DropdownMenuPopup(expanded = open, onDismissRequest = { open = false }, modifier = rememberMenuScrollModifier()) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                hidden.forEachIndexed { index, filter ->
                    DropdownMenuItem(
                        text = { Text(filter.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        shape = MenuDefaults.itemShape(index, hidden.size).shape,
                        leadingIcon = {
                            if (filter.selected) Icon(Icons.Rounded.Check, contentDescription = null)
                            else Spacer(Modifier.size(24.dp))
                        },
                        onClick = filter.onToggle
                    )
                }
            }
        }
    }
}

/** The count line under the filters ("Nodes: 214 of 2670"). */
@Composable
fun SearchCountLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp)
    )
}

/**
 * Nothing matches: the icon, [title] ("No nodes match “x”"), and while filters are on, [body] and a tonal button
 * lifting them ([liftLabel], [onLift]).
 */
@Composable
fun SearchEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    liftLabel: String? = null,
    liftIcon: ImageVector? = null,
    onLift: (() -> Unit)? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp)
    ) {
        Icon(Icons.Rounded.SearchOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        if (liftLabel != null && onLift != null) {
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onLift) {
                if (liftIcon != null) {
                    Icon(liftIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                }
                Text(liftLabel)
            }
        }
    }
}

/** [text] with the first occurrence of [part] (case ignored) in bold: a search's match in a result. */
fun highlighted(text: String, part: String?): AnnotatedString {
    if (part.isNullOrEmpty()) return AnnotatedString(text)
    val at = text.indexOf(part, ignoreCase = true)
    if (at < 0) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text.substring(0, at))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(at, at + part.length)) }
        append(text.substring(at + part.length))
    }
}
