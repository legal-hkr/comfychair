package sh.hnet.comfychair.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class NodeErrorsTest {

    // A /prompt 400 from ComfyUI: a combo value not in its list
    private val nodeErrors = JSONObject(
        """
        {"4": {"errors": [{"type": "value_not_in_list", "message": "Value not in list",
                           "details": "bit_depth: 'no_such_depth' not in ['auto', 8, 10]",
                           "extra_info": {"input_name": "bit_depth", "received_value": "no_such_depth"}}],
               "dependent_outputs": ["5"], "class_type": "CreateVideo"}}
        """
    )

    @Test
    fun `short descriptions name the node and the input`() {
        assertEquals(listOf("CreateVideo (bit_depth): Value not in list"), NodeErrors.describe(nodeErrors))
    }

    @Test
    fun `full descriptions add the server's details`() {
        assertEquals(
            listOf("CreateVideo (bit_depth): Value not in list – bit_depth: 'no_such_depth' not in ['auto', 8, 10]"),
            NodeErrors.describe(nodeErrors, withDetails = true)
        )
    }
}
