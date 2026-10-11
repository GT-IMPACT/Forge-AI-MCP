package space.impact.forgeaimcp.editor

import com.google.gson.*
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.Executors

/** Loopback HTTP transport. Callbacks own game-thread scheduling. */
class EditorServer(
    private val port: Int,
    private val drafts: DraftStore,
    private val query: (String, JsonObject) -> JsonObject,
    private val icon: (JsonObject) -> ByteArray,
    private val export: (Boolean) -> JsonObject,
) {
    private var server: HttpServer? = null
    private var workers: java.util.concurrent.ExecutorService? = null
    fun address() = "http://127.0.0.1:${server?.address?.port ?: port}"
    @Synchronized fun start(): Boolean {
        if (server != null) return false
        val listener = HttpServer.create(InetSocketAddress("127.0.0.1", port), 16)
        val pool = Executors.newFixedThreadPool(4) { task -> Thread(task, "ForgeMCP editor").apply { isDaemon = true } }
        listener.executor = pool
        listener.createContext("/") { handle(it) }
        server = listener; workers = pool
        listener.start()
        return true
    }
    @Synchronized fun stop() {
        server?.stop(0); server = null
        workers?.shutdownNow(); workers = null
    }
    private fun handle(request: HttpExchange) {
        try {
            val host = request.requestHeaders.getFirst("Host") ?: ""
            require(host == URI(address()).authority) { "Invalid Host" }
            val path = request.requestURI.path
            if (request.requestMethod == "POST") {
                require(request.requestHeaders.getFirst("X-ForgeMCP") == "editor") { "Editor request header required" }
                val origin = request.requestHeaders.getFirst("Origin")
                require(origin == null || origin == address()) { "Invalid Origin" }
            } else require(request.requestMethod == "GET") { "Method not supported" }
            when {
                request.requestMethod == "GET" && path in listOf("/", "/index.html", "/app.js", "/board.js", "/graph.js", "/style.css") -> {
                    val filename = if (path == "/") "index.html" else path.substring(1)
                    val resource = javaClass.getResourceAsStream("/assets/forgeaimcp/editor/$filename") ?: error("Missing bundled editor: $filename")
                    val mime = when (filename.substringAfterLast('.')) { "js" -> "text/javascript"; "css" -> "text/css"; else -> "text/html" }
                    respond(request, 200, "$mime; charset=utf-8", resource.use { it.readBytes() })
                }
                request.requestMethod == "POST" && path == "/api/query" -> {
                    val body = body(request)
                    val tool = body.get("tool")?.asString ?: error("Missing tool")
                    require(tool in READ_TOOLS) { "Unsupported editor query" }
                    json(request, query(tool, body.getAsJsonObject("arguments") ?: JsonObject()))
                }
                path == "/api/drafts" && request.requestMethod == "GET" -> json(request, drafts.list())
                path == "/api/drafts" && request.requestMethod == "POST" -> json(request, drafts.save(body(request)))
                path.startsWith("/api/drafts/") && request.requestMethod == "GET" -> json(request, drafts.load(path.substringAfterLast('/')))
                path == "/api/export" && request.requestMethod == "POST" -> { body(request); json(request, export(true)) }
                path == "/api/export" && request.requestMethod == "GET" -> json(request, export(false))
                path == "/api/icon" && request.requestMethod == "GET" -> {
                    require(request.requestURI.rawQuery.orEmpty().length <= 16384) { "Icon query too long" }
                    val args = JsonObject()
                    request.requestURI.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.forEach { pair ->
                        val parts = pair.split('=', limit = 2)
                        val key = URLDecoder.decode(parts[0], "UTF-8")
                        val value = URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
                        require(key in listOf("item", "metadata", "nbt", "handler", "overlay", "v")) { "Invalid icon argument" }
                        if (key == "v") { require(value == IconPixels.RENDER_VERSION.toString()) { "Unsupported icon renderer version" }; return@forEach }
                        if (key == "metadata") args.addProperty(key, value.toInt()) else args.addProperty(key, value)
                    }
                    respond(request, 200, "image/png", icon(args))
                }
                else -> respond(request, 404, "text/plain", "Not found".toByteArray())
            }
        } catch (e: Exception) {
            runCatching { respond(request, 400, "application/json; charset=utf-8", JsonObject().apply { addProperty("error", e.cause?.message ?: e.message ?: "Request failed") }.toString().toByteArray(Charsets.UTF_8)) }
        } finally { request.close() }
    }
    private fun body(request: HttpExchange): JsonObject {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val n = request.requestBody.read(buffer)
            if (n < 0) break
            require(output.size() + n <= DraftStore.MAX_BYTES) { "Request is too large" }
            output.write(buffer, 0, n)
        }
        return JsonParser().parse(String(output.toByteArray(), Charsets.UTF_8)).asJsonObject
    }
    private fun json(request: HttpExchange, value: JsonObject) = respond(request, 200, "application/json; charset=utf-8", value.toString().toByteArray(Charsets.UTF_8))
    private fun respond(request: HttpExchange, status: Int, mime: String, value: ByteArray) {
        request.responseHeaders.set("Content-Type", mime)
        request.responseHeaders.set("X-Content-Type-Options", "nosniff")
        request.responseHeaders.set("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; frame-ancestors 'none'")
        request.responseHeaders.set("Cache-Control", if (mime == "image/png") "private, max-age=3600" else "no-store")
        request.sendResponseHeaders(status, value.size.toLong())
        request.responseBody.use { it.write(value) }
    }
    companion object {
        val READ_TOOLS = setOf("get_status", "list_adapters", "list_recipe_maps", "search_items", "get_item", "search_fluids", "search_oredict", "get_oredict", "get_recipes", "get_usages", "search_machine_recipes", "get_machine_recipe", "get_recipe_authoring_guide")
    }
}
