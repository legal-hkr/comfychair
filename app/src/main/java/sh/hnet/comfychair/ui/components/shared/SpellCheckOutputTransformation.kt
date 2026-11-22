package sh.hnet.comfychair.ui.components.shared

import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SpellCheckerSession.SpellCheckerSessionListener
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

private const val SPELL_CHECK_DEBOUNCE_MS = 600L

/**
 * Returns an [OutputTransformation] that underlines misspelled words in [state] using the device's spell checker
 * service ([TextServicesManager]), or null when there is nothing to underline.
 *
 * Checks are debounced: [SPELL_CHECK_DEBOUNCE_MS] ms must pass without text changes before the checker is invoked.
 * When [enabled] is false, no session is opened.
 */
@Composable
fun rememberSpellCheckOutputTransformation(
    state: TextFieldState,
    enabled: Boolean
): OutputTransformation? {
    val context = LocalContext.current
    val underlineColor = MaterialTheme.colorScheme.error
    var misspelledRanges by remember { mutableStateOf<List<TextRange>>(emptyList()) }
    // Plain array holder – mutations don't need to trigger recomposition
    val sessionHolder = remember { arrayOfNulls<SpellCheckerSession>(1) }

    // Open / close the SpellCheckerSession when enabled changes
    DisposableEffect(enabled) {
        if (enabled) {
            val tsm = context.getSystemService(TextServicesManager::class.java)
            val listener = object : SpellCheckerSessionListener {
                override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) = Unit

                override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
                    results ?: return
                    val ranges = mutableListOf<TextRange>()
                    for (result in results) {
                        for (i in 0 until result.suggestionsCount) {
                            val info = result.getSuggestionsInfoAt(i)
                            if ((info.suggestionsAttributes and
                                        SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO) != 0
                            ) {
                                val start = result.getOffsetAt(i)
                                ranges.add(TextRange(start, start + result.getLengthAt(i)))
                            }
                        }
                    }
                    misspelledRanges = ranges
                }
            }
            @Suppress("DEPRECATION")
            sessionHolder[0] = tsm.newSpellCheckerSession(null, null, listener, true)
        }
        onDispose {
            sessionHolder[0]?.close()
            sessionHolder[0] = null
            misspelledRanges = emptyList()
        }
    }

    // Debounced check – collectLatest cancels the pending one on every text change
    LaunchedEffect(state, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow { state.text.toString() }.collectLatest { text ->
            misspelledRanges = emptyList()
            if (text.isBlank()) return@collectLatest
            delay(SPELL_CHECK_DEBOUNCE_MS)
            sessionHolder[0]?.getSentenceSuggestions(arrayOf(TextInfo(text)), 3)
        }
    }

    return remember(misspelledRanges, underlineColor, enabled) {
        if (!enabled || misspelledRanges.isEmpty()) {
            null
        } else {
            SpellCheckOutputTransformation(misspelledRanges, SpanStyle(textDecoration = TextDecoration.Underline, color = underlineColor))
        }
    }
}

private class SpellCheckOutputTransformation(
    private val misspelledRanges: List<TextRange>,
    private val style: SpanStyle
) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        for (range in misspelledRanges) {
            // Clamp to current text length – text may have changed since the async check ran
            val safeStart = range.start.coerceIn(0, length)
            val safeEnd = range.end.coerceIn(safeStart, length)
            if (safeStart < safeEnd) addStyle(style, safeStart, safeEnd)
        }
    }
}
