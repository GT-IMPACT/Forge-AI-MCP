package space.impact.forgeaimcp.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class McpInstallationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun extractsBundledBridgeAndWorkingConfigInPathWithSpacesAndUnicode() {
        val game = temporary.newFolder("Мир с пробелами")
        val directory = McpInstallation.install(game, 25576)
        assertEquals(File(game, "forgemcp").canonicalFile, directory)
        val jar = File(directory, "forgemcp-stdio.jar")
        assertTrue(jar.length() > 0)
        assertTrue(File(directory, "README.txt").readText().contains("/forgemcp start"))
        val config = JsonParser().parse(File(directory, "mcp.json").readText()).asJsonObject
            .getAsJsonObject("mcpServers").getAsJsonObject("forgemcp")
        assertEquals(jar.absolutePath, config.getAsJsonArray("args")[1].asString)
        assertEquals("25576", config.getAsJsonArray("args")[3].asString)
        assertTrue(File(config.get("command").asString).isFile)
        assertTrue(File(directory, "codex.toml").readText().startsWith("[mcp_servers.forgemcp]"))

        val command = listOf(config.get("command").asString) + config.getAsJsonArray("args").map { it.asString }
        val process = ProcessBuilder(command).start()
        val output = java.util.concurrent.CompletableFuture.supplyAsync { process.inputStream.bufferedReader(Charsets.UTF_8).readLines() }
        try {
            process.outputStream.bufferedWriter(Charsets.UTF_8).use {
                it.write("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{}}}""" + "\n")
                it.write("""{"jsonrpc":"2.0","method":"notifications/initialized"}""" + "\n")
                it.write("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""" + "\n")
            }
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(process.errorStream.bufferedReader().readText(), 0, process.exitValue())
            val replies = output.get(2, TimeUnit.SECONDS)
            assertEquals(2, replies.size)
            assertEquals(7, JsonParser().parse(replies[1]).asJsonObject.getAsJsonObject("result").getAsJsonArray("tools").size())
        } finally {
            process.destroyForcibly()
        }
    }

    @Test fun repeatInstallationIsStableAndRepairsManagedFilesWithoutTouchingOtherFiles() {
        val game = temporary.newFolder()
        val directory = McpInstallation.install(game, 25576)
        val jar = File(directory, "forgemcp-stdio.jar")
        val original = jar.readBytes()
        assertTrue(jar.setLastModified(1000000000000L))
        val timestamp = jar.lastModified()
        val custom = File(directory, "personal.json").apply { writeText("keep me") }
        McpInstallation.install(game, 25576)
        assertEquals(timestamp, jar.lastModified())
        jar.writeText("broken jar")
        McpInstallation.install(game, 25577)
        assertArrayEquals(original, jar.readBytes())
        assertEquals("keep me", custom.readText())
        val config = JsonParser().parse(File(directory, "mcp.json").readText()).asJsonObject
            .getAsJsonObject("mcpServers").getAsJsonObject("forgemcp")
        assertEquals("25577", config.getAsJsonArray("args")[3].asString)
        assertTrue(File(directory, "codex.toml").readText().contains("25577"))
    }
}
