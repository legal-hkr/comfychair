package sh.hnet.comfychair.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerWorkflowsTest {

    @Test
    fun listKeepsJsonFilesNewestFirst() {
        val list = ServerWorkflows.parseList(
            """[
                {"path": "old.json", "size": 10, "modified": 1000},
                {"path": "video/wan22_i2v.json", "size": 20, "modified": 3000.5},
                {"path": "notes.txt", "size": 5, "modified": 4000},
                {"path": "edit\\qwen.json", "size": 30, "modified": 2000}
            ]"""
        )
        assertEquals(listOf("video/wan22_i2v.json", "edit/qwen.json", "old.json"), list.map { it.path })
        assertEquals("wan22_i2v", list[0].name)
        assertEquals("video", list[0].folder)
        assertEquals("", list[2].folder)
        assertEquals(3000L, list[0].modified)
    }

    @Test
    fun listDropsUnsafePaths() {
        val list = ServerWorkflows.parseList(
            """[{"path": "../comfy.settings.json"}, {"path": "/etc/x.json"}, {"path": "a//b.json"}, {"path": "./a.json"}, {"path": "ok.json"}]"""
        )
        assertEquals(listOf("ok.json"), list.map { it.path })
    }

    @Test
    fun safePaths() {
        assertTrue(ServerWorkflows.isSafePath("a.json"))
        assertTrue(ServerWorkflows.isSafePath("video/tests/2025-october/a b.json"))
        assertFalse(ServerWorkflows.isSafePath("video/../../secret.json"))
        assertFalse(ServerWorkflows.isSafePath(".."))
        assertFalse(ServerWorkflows.isSafePath(""))
        assertFalse(ServerWorkflows.isSafePath("a\\b.json"))
    }

    @Test
    fun tooLargeAboveTheLimit() {
        assertFalse(ServerWorkflow("a.json", ServerWorkflows.MAX_BYTES, 0).isTooLarge)
        assertTrue(ServerWorkflow("a.json", ServerWorkflows.MAX_BYTES + 1, 0).isTooLarge)
    }

    @Test
    fun multiUserFromUsers() {
        assertFalse(ServerWorkflows.isMultiUser("""{"storage": "server", "migrated": true}"""))
        assertTrue(ServerWorkflows.isMultiUser("""{"storage": "server", "users": {"abc": "kasia"}}"""))
        assertFalse(ServerWorkflows.isMultiUser("not json"))
    }

    @Test
    fun workflowFormats() {
        assertNull(ServerWorkflows.workflowProblem("""{"last_node_id": 1, "nodes": [], "links": []}"""))
        assertNull(ServerWorkflows.workflowProblem("""{"3": {"class_type": "KSampler", "inputs": {}}}"""))
        assertNotNull(ServerWorkflows.workflowProblem("""{"Comfy.ColorPalette": "dark"}"""))
        assertNotNull(ServerWorkflows.workflowProblem("""{}"""))
        assertNotNull(ServerWorkflows.workflowProblem("[1, 2]"))
    }
}
