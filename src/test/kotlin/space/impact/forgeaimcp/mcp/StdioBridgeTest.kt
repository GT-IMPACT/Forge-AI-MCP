package space.impact.forgeaimcp.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import space.impact.forgeaimcp.editor.DraftStore
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture

class StdioBridgeTest {
    @get:Rule val temporary = TemporaryFolder()
    private val initialize = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{}}}"""
    private val initialized = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""

    private fun run(port: Int, vararg messages: String): List<JsonObject> {
        val java = File(System.getProperty("java.home"), "bin/java" + if (File.separatorChar == '\\') ".exe" else "")
        val process = ProcessBuilder(java.absolutePath, "-jar", System.getProperty("forgemcp.bridgeJar"), "--port", port.toString()).start()
        val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader(Charsets.UTF_8).readLines() }
        try {
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                messages.forEach { writer.write(it); writer.newLine() }
            }
            assertTrue("stdio process hung", process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(process.errorStream.bufferedReader().readText(), 0, process.exitValue())
            return output.get(2, TimeUnit.SECONDS).map { JsonParser().parse(it).asJsonObject }
        } finally {
            process.destroyForcibly()
        }
    }

    @Test fun handshakeAndDiscoveryWorkWithoutGameAndDoNotStartListener() {
        val bridge = LocalBridge(25576) { _, _ -> error("Must not query game during discovery") }
        val replies = run(25576, initialize, initialized, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
        assertFalse(bridge.isRunning())
        assertEquals(2, replies.size)
        assertEquals("2025-06-18", replies[0].getAsJsonObject("result").get("protocolVersion").asString)
        val tools = replies[1].getAsJsonObject("result").getAsJsonArray("tools")
        assertEquals(15, tools.size())
        val names = tools.map { it.asJsonObject.get("name").asString }
        assertTrue(names.containsAll(listOf("list_adapters", "search_machine_recipes", "get_recipe_authoring_guide")))
        assertFalse(names.contains("generate_recipe_code"))
        tools.forEach { assertTrue(it.asJsonObject.getAsJsonObject("annotations").get("readOnlyHint").asBoolean) }
    }

    @Test fun malformedMessagesAndInvalidArgumentsRemainProtocolErrors() {
        val bridge = LocalBridge(0) { _, _ -> error("Invalid request was forwarded") }
        try {
            bridge.start()
            val replies = run(bridge.localPort(), "invalid json", initialize, initialized,
                """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"search_items","arguments":{"limit":true}}}""",
                """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"search_items","arguments":{"limit":101}}}""",
                """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"get_item","arguments":{}}}""",
                """{"jsonrpc":"2.0","id":5,"method":"unknown"}""")
            assertEquals(-32700, replies[0].getAsJsonObject("error").get("code").asInt)
            replies.subList(2, 5).forEach { assertEquals(-32602, it.getAsJsonObject("error").get("code").asInt) }
            assertEquals(-32601, replies[5].getAsJsonObject("error").get("code").asInt)
        } finally { bridge.stop() }
    }

    @Test fun realStdioToJvmBridgePreservesUnicodeNbtWildcardPaginationAndWarnings() {
        val data = JsonParser().parse("""{"results":[{"name":"Железо","metadata":32767,"nbt":"{display:{Name:\"Железо\"}}"}],"offset":20,"hasMore":true,"complete":false,"warnings":["broken handler"]}""").asJsonObject
        val bridge = LocalBridge(0) { tool, args ->
            check(tool == "get_oredict")
            check(args.get("name").asString == "logWood")
            check(args.get("offset").asInt == 20)
            data
        }
        try {
            bridge.start()
            val replies = run(bridge.localPort(), initialize, initialized,
                """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_oredict","arguments":{"name":"logWood","offset":20}}}""")
            val result = replies[1].getAsJsonObject("result")
            assertFalse(result.get("isError").asBoolean)
            assertEquals(data, result.getAsJsonObject("structuredContent"))
            assertEquals(data, JsonParser().parse(result.getAsJsonArray("content")[0].asJsonObject.get("text").asString))
        } finally { bridge.stop() }
    }

    @Test fun gameFailureIsToolErrorAndInitializationIsRequired() {
        val bridge = LocalBridge(0) { _, _ -> error("NEI still loading") }
        try {
            bridge.start()
            val replies = run(bridge.localPort(), """{"jsonrpc":"2.0","id":0,"method":"tools/list"}""", initialize, initialized,
                """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"search_items"}}""")
            assertEquals(-32002, replies[0].getAsJsonObject("error").get("code").asInt)
            assertTrue(replies[2].getAsJsonObject("result").get("isError").asBoolean)
            assertTrue(replies[2].toString().contains("NEI still loading"))
        } finally { bridge.stop() }
    }

    @Test fun adapterArgumentsAndBooleanFiltersCrossStdioWithSchemaValidation() {
        var calls = 0
        val bridge = LocalBridge(0) { tool, args ->
            check(tool == "search_machine_recipes")
            check(args.get("adapter").asString == "gregtech-impact")
            check(args.get("map").asString == "sBlastRecipes")
            check(args.get("includeHidden").asBoolean)
            calls++
            JsonObject().apply { addProperty("adapter", "gregtech-impact"); addProperty("total", 1) }
        }
        try {
            bridge.start()
            val replies = run(bridge.localPort(), initialize, initialized,
                """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"search_machine_recipes","arguments":{"adapter":"gregtech-impact","map":"sBlastRecipes","includeHidden":true}}}""",
                """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"search_machine_recipes","arguments":{"adapter":"gregtech-impact","includeHidden":"true"}}}""",
                """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"list_recipe_maps","arguments":{}}}""")
            assertFalse(replies[1].getAsJsonObject("result").get("isError").asBoolean)
            assertEquals("gregtech-impact", replies[1].getAsJsonObject("result").getAsJsonObject("structuredContent").get("adapter").asString)
            assertEquals(-32602, replies[2].getAsJsonObject("error").get("code").asInt)
            assertEquals(-32602, replies[3].getAsJsonObject("error").get("code").asInt)
            assertEquals(1, calls)
        } finally { bridge.stop() }
    }

    @Test fun stdioReadsPersistedVisualDraftWithoutGeneratingOrApplyingCode() {
        val store = DraftStore(temporary.newFolder())
        val saved = store.save(JsonParser().parse("""{"schemaVersion":1,"title":"ME Controller","adapter":"gregtech-impact","map":"sAssemblerRecipes","kind":"machine","durationTicks":600,"eut":1920,"specialValue":0,"itemInputs":[],"itemOutputs":[{"item":"appliedenergistics2:tile.BlockController","metadata":0,"count":1,"chance":10000}],"notes":"ИИ пишет Kotlin самостоятельно"}""").asJsonObject)
        val id = saved.get("id").asString
        val bridge = LocalBridge(0) { tool,args ->
            when(tool) {
                "list_recipe_drafts" -> store.list()
                "get_recipe_draft" -> store.load(args.get("id").asString)
                else -> error("Unexpected tool")
            }
        }
        try {
            bridge.start()
            val replies = run(bridge.localPort(),initialize,initialized,
                """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_recipe_drafts"}}""",
                """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_recipe_draft","arguments":{"id":"$id"}}}""")
            assertEquals(id,replies[1].getAsJsonObject("result").getAsJsonObject("structuredContent").getAsJsonArray("results")[0].asJsonObject.get("id").asString)
            assertEquals(saved,replies[2].getAsJsonObject("result").getAsJsonObject("structuredContent"))
        } finally { bridge.stop() }
    }
}
