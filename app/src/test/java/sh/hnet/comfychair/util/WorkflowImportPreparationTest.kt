package sh.hnet.comfychair.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowImportPreparationTest {
    @Test fun liteGraphNeedsNonEmptyNodesAndLinks() {
        assertTrue(WorkflowImportPreparation.isImportableJson("""{"nodes":[{"id":1}],"links":[]}"""))
        assertFalse(WorkflowImportPreparation.isImportableJson("""{"nodes":[],"links":[]}"""))
        assertFalse(WorkflowImportPreparation.isImportableJson("""{"nodes":[{"id":1}]}"""))
        assertFalse(WorkflowImportPreparation.isImportableJson("""{"nodes":{},"links":[]}"""))
    }

    @Test fun acceptsApiAndWrappedComfyChairNodes() {
        val nodes = """{"1":{"class_type":"CLIPTextEncode","inputs":{"text":"hello"}}}"""
        assertTrue(WorkflowImportPreparation.isImportableJson(nodes))
        assertTrue(WorkflowImportPreparation.isImportableJson("""{"nodes":$nodes,"type":"TTI"}"""))
    }

    @Test fun missingDefinitionRejectsWidgetBearingGraphRatherThanDroppingValues() {
        val graph = """{"nodes":[{"id":1,"type":"UnknownNode","pos":[0,0],"size":[200,100],"widgets_values":["hello"]}],"links":[]}"""
        assertNull(WorkflowImportPreparation.prepare(graph) { null })
        assertNull(WorkflowImportPreparation.prepare("""{"nodes":[{"id":1,"type":"sub","widgets_values":[]}],"links":[],"definitions":{"subgraphs":[{"id":"sub","nodes":[{"id":2,"type":"UnknownNode","widgets_values":["hello"]}]}]}}""") { null })
        val definition = sh.hnet.comfychair.workflow.NodeTypeDefinition("UnknownNode", "", listOf(
            sh.hnet.comfychair.workflow.InputDefinition("text", "STRING", true)
        ), emptyList())
        val result = WorkflowImportPreparation.prepare(graph) { definition }!!
        val converted = org.json.JSONObject(result.jsonContent)
        org.junit.Assert.assertEquals("hello", converted.getJSONObject("nodes").getJSONObject("1").getJSONObject("inputs").getString("text"))
        assertTrue(result.warnings.isEmpty())
        assertTrue(WorkflowImportPreparation.prepare("""{"nodes":[{"id":2,"type":"UnknownNode","widgets_values":[]}],"links":[]}""") { null } != null)
        // API imports require no /object_info lookup.
        assertTrue(WorkflowImportPreparation.prepare("""{"1":{"class_type":"CLIPTextEncode","inputs":{}}}""") { null }!!.warnings.isEmpty())
        assertTrue(WorkflowImportPreparation.prepare("[]") { null } == null)
    }

    @Test fun invalidAndEmptyJsonDoNotOpenImport() {
        listOf("", "[]", "{broken", "{}", "{\"nodes\":42}", "{\"1\":{\"inputs\":{}}}")
            .forEach { assertFalse(it, WorkflowImportPreparation.isImportableJson(it)) }
    }
}
