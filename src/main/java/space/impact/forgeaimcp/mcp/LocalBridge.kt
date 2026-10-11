package space.impact.forgeaimcp.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Transport only; construction does not bind a port or create threads. */
class LocalBridge(private val port: Int, private val query: (String, JsonObject) -> JsonObject) {
    @Volatile private var listener: ServerSocket? = null
    private var workers: ThreadPoolExecutor? = null
    private val connections = Collections.synchronizedSet(HashSet<Socket>())

    fun isRunning() = listener?.isClosed == false
    fun localPort() = listener?.localPort ?: port

    @Synchronized
    fun start(): Boolean {
        if (isRunning()) return false
        val server = ServerSocket()
        try {
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8)
        } catch (e: Exception) {
            server.close()
            throw e
        }
        val pool = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8),
            { task -> Thread(task, "ForgeMCP worker").apply { isDaemon = true } })
        listener = server
        workers = pool
        Thread({
            try {
                while (!server.isClosed) {
                    val socket = server.accept()
                    connections.add(socket)
                    try {
                        pool.execute { handle(socket) }
                    } catch (e: RejectedExecutionException) {
                        connections.remove(socket)
                        socket.close()
                    }
                }
            } catch (e: java.io.IOException) {
                // Close the bridge on a listener failure; never restart automatically.
            } finally {
                synchronized(this) {
                    if (listener === server) stop()
                }
            }
        }, "ForgeMCP listener").apply { isDaemon = true }.start()
        return true
    }

    @Synchronized
    fun stop(): Boolean {
        val server = listener ?: return false
        listener = null
        server.close()
        synchronized(connections) {
            connections.forEach { runCatching { it.close() } }
            connections.clear()
        }
        workers?.shutdownNow()
        workers = null
        return true
    }

    private fun handle(socket: Socket) {
        try {
            socket.use {
                socket.soTimeout = 15000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val response = try {
                    val request = JsonParser().parse(readRequest(reader)).asJsonObject
                    val tool = request.get("tool")?.asString ?: error("Missing tool")
                    val args = request.getAsJsonObject("arguments") ?: JsonObject()
                    JsonObject().apply { add("result", query(tool, args)) }
                } catch (e: Exception) {
                    JsonObject().apply { addProperty("error", e.cause?.message ?: e.message ?: "Request failed") }
                }
                val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                writer.write(response.toString())
                writer.newLine()
                writer.flush()
            }
        } catch (e: java.io.IOException) {
            // A stopped bridge, timed-out peer or disconnected client needs no retry.
        } finally {
            connections.remove(socket)
        }
    }

    private fun readRequest(reader: BufferedReader): String {
        val line = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c == -1 || c == 10) break
            require(line.length < 65536) { "Request exceeds 64 KiB" }
            line.append(c.toChar())
        }
        require(line.isNotEmpty()) { "Empty request" }
        return line.toString()
    }
}
