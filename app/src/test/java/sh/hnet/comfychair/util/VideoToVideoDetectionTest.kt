package sh.hnet.comfychair.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.WorkflowValidationResult
import sh.hnet.comfychair.workflow.TemplateKeyRegistry

/** Video to Video workflows are recognised on import, whichever node loads the video, and pass the required fields. */
class VideoToVideoDetectionTest {

    // ComfyUI's own nodes: the video file is LoadVideo's "file"; "video" is also the name of the VIDEO links
    private val loadVideo = """
        {
          "1": {"class_type": "LoadVideo", "inputs": {"file": "example.mp4"}},
          "2": {"class_type": "GetVideoComponents", "inputs": {"video": ["1", 0]}},
          "3": {"class_type": "CreateVideo", "inputs": {"images": ["2", 0], "fps": ["2", 2], "audio": ["2", 1]}},
          "4": {"class_type": "SaveVideo", "inputs": {"video": ["3", 0], "filename_prefix": "video/ComfyChair", "format": "auto", "codec": "auto"}}
        }
    """.trimIndent()

    // VideoHelperSuite: the file is VHS_LoadVideo's "video"
    private val vhsLoadVideo = """
        {
          "1": {"class_type": "VHS_LoadVideo", "inputs": {"video": "example.mp4", "force_rate": 0, "custom_width": 0, "custom_height": 0, "frame_load_cap": 0, "skip_first_frames": 0, "select_every_nth": 1}},
          "2": {"class_type": "VHS_VideoCombine", "inputs": {"images": ["1", 0], "audio": ["1", 2], "frame_rate": 24, "loop_count": 0, "filename_prefix": "ComfyChair", "format": "video/h264-mp4", "pingpong": false, "save_output": true}}
        }
    """.trimIndent()

    // A video workflow without a video loader stays Text to Video
    private val noLoader = """
        {
          "1": {"class_type": "EmptyHunyuanLatentVideo", "inputs": {"width": 832, "height": 480, "length": 49, "batch_size": 1}},
          "2": {"class_type": "CreateVideo", "inputs": {"images": ["1", 0], "fps": 24}}
        }
    """.trimIndent()

    @Test
    fun `a workflow loading a video is Video to Video`() {
        assertEquals(WorkflowType.V2V, WorkflowJsonAnalyzer.detectWorkflowType(loadVideo))
        assertEquals(WorkflowType.V2V, WorkflowJsonAnalyzer.detectWorkflowType(vhsLoadVideo))
        assertEquals(WorkflowType.TTV, WorkflowJsonAnalyzer.detectWorkflowType(noLoader))
    }

    @Test
    fun `the source video field is found in either loader`() {
        assertEquals(WorkflowValidationResult.Success, WorkflowJsonAnalyzer.validateWorkflowKeys(loadVideo, WorkflowType.V2V))
        assertEquals(WorkflowValidationResult.Success, WorkflowJsonAnalyzer.validateWorkflowKeys(vhsLoadVideo, WorkflowType.V2V))
    }

    @Test
    fun `only a file name counts as the source video, not a VIDEO link`() {
        assertTrue(TemplateKeyRegistry.isFieldMatch("video", "file", "example.mp4"))
        assertTrue(TemplateKeyRegistry.isFieldMatch("video", "video", "example.mp4"))
        assertFalse(TemplateKeyRegistry.isFieldMatch("video", "video", null))
    }

    @Test
    fun `the prefix v2v_ means Video to Video`() {
        assertEquals(WorkflowType.V2V, WorkflowJsonAnalyzer.parseWorkflowType("v2v_seedvr2.json"))
    }
}
