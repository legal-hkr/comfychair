package sh.hnet.comfychair

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source contract only; executable paging state cases live in ServerPickerScrollPolicyTest. */
class ServerPickerScrollRegressionTest {
    @Test fun ordinaryScrollingMustNotHaveASessionWidePageQuota() {
        val panel = listOf(File("src/main/java/sh/hnet/comfychair/ui/components/ServerImagePickerPanel.kt"),
            File("app/src/main/java/sh/hnet/comfychair/ui/components/ServerImagePickerPanel.kt")).first { it.isFile }.readText()
        assertFalse("Five-page session quota blocks ordinary scrolling", panel.contains("autoPages < 5"))
    }
}
