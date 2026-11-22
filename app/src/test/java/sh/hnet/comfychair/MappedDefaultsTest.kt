package sh.hnet.comfychair

import org.json.JSONObject
import sh.hnet.comfychair.model.WorkflowDefaults
import org.junit.Assert.assertEquals
import org.junit.Test

class MappedDefaultsTest {

    private val fileDefaults = WorkflowDefaults.fromJson(JSONObject("""{"width": 832, "height": 1216, "steps": 9, "cfg": 1.0, "sampler_name": "euler", "scheduler": "simple", "negative_prompt": "blurry ugly bad", "denoise": 1.0}"""))

    @Test
    fun workflowHoldingPlaceholders_keepsItsOwnDefaults() {
        // An exported ComfyChair workflow: every mapped value is a placeholder already, nothing to extract
        val defaults = WorkflowManager.mappedDefaults(fileDefaults, emptyMap())
        assertEquals(9, defaults.steps)
        assertEquals(1.0f, defaults.cfg)
        assertEquals(832, defaults.width)
        assertEquals("blurry ugly bad", defaults.negativePrompt)
    }

    @Test
    fun valuesTakenFromTheNodes_winOverTheFilesDefaults() {
        val defaults = WorkflowManager.mappedDefaults(fileDefaults, mapOf("steps" to 25, "cfg" to 4.5))
        assertEquals(25, defaults.steps)
        assertEquals(4.5f, defaults.cfg)
        assertEquals("euler", defaults.samplerName)
    }

    @Test
    fun advancedValuesTakenFromTheNodes_winToo() {
        val defaults = WorkflowManager.mappedDefaults(fileDefaults, mapOf("denoise" to 0.6, "batch_size" to 2))
        assertEquals(0.6f, defaults.denoise)
        assertEquals(2, defaults.batchSize)
    }

    @Test
    fun fileWithoutDefaults_takesOnlyTheNodesValues() {
        val defaults = WorkflowManager.mappedDefaults(WorkflowDefaults(), mapOf("steps" to 30))
        assertEquals(30, defaults.steps)
        assertEquals(null, defaults.cfg)
    }
}
