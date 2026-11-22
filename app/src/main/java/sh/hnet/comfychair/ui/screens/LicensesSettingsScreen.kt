package sh.hnet.comfychair.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.settings.SettingsGroup
import sh.hnet.comfychair.ui.components.settings.SettingsPage
import sh.hnet.comfychair.ui.components.settings.SettingsTextRow

/** A license the shipped components come under; [asset] is its full text in the assets. */
private enum class License(@param:StringRes val label: Int, val asset: String) {
    APACHE(R.string.label_apache_license, "licenses/apache-2.0.txt"),
    BSD_2_CLAUSE_COMMONMARK(R.string.label_bsd_2_clause_license, "licenses/bsd-2-clause-commonmark.txt"),
}

/**
 * What ships in the app, with its license (checked against the release runtime classpath; update when a dependency is
 * added). A BSD license's text carries the copyright line of its component, so each BSD component has a text of its
 * own. Tink and Gson come only with AndroidX Security: remove them together with it.
 */
private val COMPONENTS = listOf(
    "AndroidX (Jetpack Compose, Material 3, Media3, Navigation, Lifecycle, Activity, AppCompat, Core, Security)" to License.APACHE,
    "Material Components for Android" to License.APACHE,
    "Kotlin" to License.APACHE,
    "kotlinx.coroutines" to License.APACHE,
    "kotlinx.serialization" to License.APACHE,
    "OkHttp" to License.APACHE,
    "Okio" to License.APACHE,
    "commonmark-java" to License.BSD_2_CLAUSE_COMMONMARK,
    "Tink" to License.APACHE,
    "Gson" to License.APACHE,
    "Guava" to License.APACHE,
    "Error Prone Annotations" to License.APACHE,
    "JSpecify" to License.APACHE,
    "JetBrains Java Annotations" to License.APACHE,
    "Material Icons" to License.APACHE,
    "Material Symbols" to License.APACHE,
)

/** Settings › About › Open-source licenses, as in the family's other apps: each component and its license, the text on tap. */
@Composable
fun LicensesSettingsScreen(onNavigateBack: () -> Unit) {
    var showing by rememberSaveable { mutableStateOf<License?>(null) }
    SettingsPage(stringResource(R.string.label_licenses), onNavigateBack) {
        SettingsGroup {
            COMPONENTS.forEach { (name, license) ->
                row { shapes ->
                    SettingsTextRow(shapes, name, stringResource(license.label)) { showing = license }
                }
            }
        }
    }
    showing?.let { license ->
        val context = LocalContext.current
        val text by produceState<String?>(null, license) {
            value = withContext(Dispatchers.IO) {
                runCatching { context.assets.open(license.asset).bufferedReader().use { it.readText() } }.getOrNull()
            }
        }
        AlertDialog(
            onDismissRequest = { showing = null },
            title = { Text(stringResource(license.label)) },
            text = {
                SelectionContainer(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    Text(text.orEmpty(), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showing = null }) { Text(stringResource(R.string.button_close)) } }
        )
    }
}
