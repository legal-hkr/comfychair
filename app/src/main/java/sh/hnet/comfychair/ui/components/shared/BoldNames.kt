package sh.hnet.comfychair.ui.components.shared

import android.text.TextUtils
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml

/**
 * A string whose `<b>` parts are bold: names of fields, inputs and nodes in a message, so they stand out from the
 * sentence. The arguments are escaped, so a name can't add markup.
 */
@Composable
fun boldNamesResource(@StringRes id: Int, vararg names: String): AnnotatedString {
    val resources = LocalResources.current
    val escaped = names.map { TextUtils.htmlEncode(it) }.toTypedArray()
    return AnnotatedString.fromHtml(resources.getString(id, *escaped))
}
