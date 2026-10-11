package space.impact.forgeaimcp.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class LocalBridgeTest {
    private fun request(port: Int, body: String): JsonObject = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 2000
        socket.getOutputStream().write((body + "\n").toByteArray(Charsets.UTF_8))
        JsonParser().parse(socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine()).asJsonObject
    }

    @Test fun manualLifecycleAndUnicodeRoundTrip() {
        val bridge = LocalBridge(0) { tool, args ->
            assertEquals("search_items", tool)
            JsonObject().apply { addProperty("name", args.get("query").asString) }
        }
        assertFalse(bridge.isRunning())
        assertFalse(bridge.stop())
        try {
            assertTrue(bridge.start())
            assertFalse(bridge.start())
            assertTrue(bridge.isRunning())
            val response = request(bridge.localPort(), """{"tool":"search_items","arguments":{"query":"Железо"}}""")
            assertEquals("Железо", response.getAsJsonObject("result").get("name").asString)
            assertTrue(bridge.stop())
            assertFalse(bridge.stop())
            assertFalse(bridge.isRunning())
            assertTrue(bridge.start())
        } finally {
            bridge.stop()
        }
    }

    @Test fun occupiedPortDoesNotEnableBridgeAndCanRecover() {
        val occupied = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val bridge = LocalBridge(occupied.localPort) { _, _ -> JsonObject() }
        try {
            assertFalse(bridge.isRunning())
            try {
                bridge.start()
                fail("Expected occupied port failure")
            } catch (expected: BindException) {
                assertFalse(bridge.isRunning())
            }
            occupied.close()
            assertTrue(bridge.start())
        } finally {
            occupied.close()
            bridge.stop()
        }
    }

    @Test fun malformedRequestsAndToolFailuresDoNotKillListener() {
        val bridge = LocalBridge(0) { tool, _ ->
            check(tool != "fail") { "catalog still loading" }
            JsonObject()
        }
        try {
            bridge.start()
            assertTrue(request(bridge.localPort(), "bad json").has("error"))
            val failure = request(bridge.localPort(), """{"tool":"fail"}""")
            assertEquals("catalog still loading", failure.get("error").asString)
            assertTrue(request(bridge.localPort(), """{"tool":"get_status"}""").has("result"))
            assertTrue(bridge.isRunning())
        } finally {
            bridge.stop()
        }
    }

    @Test fun stopClosesConnectedSocketsAndReleasesPort() {
        val bridge = LocalBridge(0) { _, _ -> JsonObject() }
        try {
            bridge.start()
            val port = bridge.localPort()
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 2000
                request(port, """{"tool":"get_status"}""")
                bridge.stop()
                try {
                    assertEquals(-1, socket.getInputStream().read())
                } catch (expected: java.net.SocketException) {
                    // Reset is also a closed connection.
                }
            }
            ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).use { assertEquals(port, it.localPort) }
        } finally {
            bridge.stop()
        }
    }
}
