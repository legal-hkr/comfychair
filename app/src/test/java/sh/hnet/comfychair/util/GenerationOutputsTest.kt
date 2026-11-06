package sh.hnet.comfychair.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationOutputsTest {

    // Shape of a LoadVideo → SaveVideo job in /history: the loader reports the source it read
    private val outputs = JSONObject(
        """
        {
          "5": {"images": [{"filename": "ComfyChair_00014_.mp4", "subfolder": "video", "type": "output",
                            "metadata": {"kind": "video", "duration": 2.0}}], "animated": [true]},
          "1": {"images": [{"filename": "v2v_source_1.mp4", "subfolder": "", "type": "input",
                            "metadata": {"kind": "video", "duration": 2.0}}], "animated": [true]}
        }
        """
    )

    @Test
    fun `source reported by a loader is not a result`() {
        val files = GenerationOutputs.parse(outputs, "p", preferSaved = false)
        assertEquals(listOf("ComfyChair_00014_.mp4"), files.map { it.filename })
        assertTrue(files.single().isVideo)
        assertEquals(2000L, files.single().durationMs)
    }

    @Test
    fun `previews are kept when nothing was saved`() {
        val previews = JSONObject(
            """{"3": {"images": [{"filename": "p.png", "subfolder": "", "type": "temp"}]},
               "1": {"images": [{"filename": "src.png", "subfolder": "", "type": "input"}]}}"""
        )
        assertEquals(listOf("p.png"), GenerationOutputs.parse(previews, "p").map { it.filename })
    }
}
