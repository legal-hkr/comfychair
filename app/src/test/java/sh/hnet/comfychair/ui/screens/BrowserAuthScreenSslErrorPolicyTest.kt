package sh.hnet.comfychair.ui.screens

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserAuthScreenSslErrorPolicyTest {
    @Test
    fun onReceivedSslErrorCancelsRatherThanProceeding() {
        val callbackBody = onReceivedSslErrorBody(browserAuthScreenSource().readText())

        assertTrue(
            "onReceivedSslError must cancel SSL-error requests",
            callbackBody.contains("handler.cancel()")
        )
        assertFalse(
            "onReceivedSslError must not bypass certificate validation",
            callbackBody.contains("handler.proceed()")
        )
    }

    private fun browserAuthScreenSource(): File {
        val candidates = listOf(
            File("src/main/java/sh/hnet/comfychair/ui/screens/BrowserAuthScreen.kt"),
            File("app/src/main/java/sh/hnet/comfychair/ui/screens/BrowserAuthScreen.kt")
        )

        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("Could not locate BrowserAuthScreen.kt from the test working directory")
    }

    private fun onReceivedSslErrorBody(source: String): String {
        val callbackStart = source.indexOf("override fun onReceivedSslError")
        assertTrue("BrowserAuthScreen must override onReceivedSslError", callbackStart >= 0)

        val bodyStart = source.indexOf('{', callbackStart)
        assertTrue("onReceivedSslError must have a callback body", bodyStart >= 0)

        var braceDepth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> braceDepth++
                '}' -> {
                    braceDepth--
                    if (braceDepth == 0) {
                        return source.substring(bodyStart + 1, index)
                    }
                }
            }
        }

        throw AssertionError("onReceivedSslError callback body is not closed")
    }
}
