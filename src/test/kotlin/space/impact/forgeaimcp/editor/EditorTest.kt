package space.impact.forgeaimcp.editor

import com.google.gson.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

class EditorTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun draft() = JsonParser().parse("""{"schemaVersion":1,"title":"Контроллер ME","adapter":"gregtech-impact","map":"sAssemblerRecipes","kind":"machine","durationTicks":600,"eut":1920,"specialValue":0,"itemInputs":[{"item":"gregtech:gt.metaitem.01","metadata":32767,"count":0,"nbt":"{test:1}","alternatives":[{"item":"test:alternative","count":2}]}],"itemOutputs":[{"item":"appliedenergistics2:tile.BlockController","count":1,"chance":2500}],"fluidInputs":[{"fluid":"oxygen","amount":144}],"fluidOutputs":[],"specialItems":[],"notes":"Напиши на Kotlin"}""").asJsonObject

    @Test fun craftingGridAndFurnaceXpRoundTripAndInvalidGridIndicesFail() {
        val store = DraftStore(temporary.newFolder())
        val shaped = draft().apply {
            addProperty("adapter", "minecraft"); addProperty("map", "crafting-shaped"); addProperty("kind", "shaped")
            add("grid", JsonParser().parse("""{"width":2,"height":2,"cells":[null,0,null,null],"mirrored":false}"""))
            addProperty("copiesInputNbt", true)
        }
        val saved = store.save(shaped)
        assertEquals(shaped.get("grid"), store.load(saved.get("id").asString).get("grid"))
        for (cells in listOf("[0,0,null,null]", "[null,2,null,null]", "[null,null,null,null]", "[0]")) {
            shaped.getAsJsonObject("grid").add("cells",JsonParser().parse(cells))
            try { store.save(shaped); fail() } catch (_: IllegalArgumentException) { }
        }
        val furnace = draft().apply { addProperty("adapter","minecraft");addProperty("kind","furnace");addProperty("map","furnace");addProperty("experience",.7) }
        val smelted = store.save(furnace)
        assertEquals(.7,store.load(smelted.get("id").asString).get("experience").asDouble,0.0)
        furnace.addProperty("experience",-1)
        try { store.save(furnace); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun draftRoundTripPreservesIdentityConditionsAndAlternativeSlots() {
        val store = DraftStore(temporary.newFolder())
        val saved = store.save(draft())
        val loaded = store.load(saved.get("id").asString)
        assertEquals(saved, loaded)
        val input = loaded.getAsJsonArray("itemInputs")[0].asJsonObject
        assertEquals(32767, input.get("metadata").asInt)
        assertEquals(0, input.get("count").asInt)
        assertEquals("{test:1}", input.get("nbt").asString)
        assertEquals(1, input.getAsJsonArray("alternatives").size())
        assertEquals(2500, loaded.getAsJsonArray("itemOutputs")[0].asJsonObject.get("chance").asInt)
        assertEquals(1, store.list().getAsJsonArray("results").size())
        val summary = store.list().getAsJsonArray("results")[0].asJsonObject
        for (key in listOf("itemInputs", "itemOutputs", "fluidInputs", "fluidOutputs")) assertEquals(loaded.get(key), summary.get(key))
        loaded.addProperty("title", "Обновлённый контроллер")
        assertEquals(saved.get("id"), store.save(loaded).get("id"))
        assertEquals(1, store.list().getAsJsonArray("results").size())
    }

    @Test fun rejectsTraversalInvalidAmountsAndOversizedDraftsWithoutCreatingFiles() {
        val folder = temporary.newFolder(); val store = DraftStore(folder)
        val cases = listOf(draft().apply { addProperty("id", "../outside") }, draft().apply { getAsJsonArray("fluidInputs")[0].asJsonObject.addProperty("amount", -1) }, draft().apply { addProperty("notes", "x".repeat(DraftStore.MAX_BYTES)) })
        for (bad in cases) {
            try { store.save(bad); fail("Must reject malformed draft") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, folder.listFiles()!!.size)
        try { store.load("../outside"); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun rgbaConversionPreservesTransparencyAndFlipsOpenGlRows() {
        val pixels = ByteBuffer.wrap(byteArrayOf(255.toByte(),0,0,255.toByte(), 0,255.toByte(),0,0, 0,0,255.toByte(),255.toByte(), 255.toByte(),255.toByte(),255.toByte(),128.toByte()))
        val image = IconPixels.image(pixels,2)
        assertEquals(0xff0000ff.toInt(), image.getRGB(0,0))
        assertEquals(0x80ffffff.toInt(), image.getRGB(1,0))
        assertEquals(0xffff0000.toInt(), image.getRGB(0,1))
        assertEquals(0x0000ff00, image.getRGB(1,1))
        val one = draft().getAsJsonArray("itemInputs")[0].asJsonObject
        val same = JsonParser().parse(one.toString()).asJsonObject.apply { addProperty("count",4); addProperty("name","localized") }
        assertEquals(IconPixels.key(one), IconPixels.key(same))
        same.addProperty("metadata",1)
        assertNotEquals(IconPixels.key(one),IconPixels.key(same))
    }

    @Test fun rendererRevisionInvalidatesDiskCacheWithoutChangingItemIdentity() {
        val profile = JsonParser().parse("""{"mods":["minecraft:1.7.10"],"resourcePacks":["default"]}""").asJsonObject
        assertNotEquals(IconPixels.cacheNamespace(profile, 1), IconPixels.cacheNamespace(profile, 2))
        assertEquals(IconPixels.cacheNamespace(profile), IconPixels.cacheNamespace(JsonParser().parse(profile.toString()).asJsonObject))
        assertFalse(profile.has("iconRendererVersion"))
        val handler = JsonParser().parse("""{"handler":"same.handler.Class","overlay":"map-one"}""").asJsonObject
        val other = JsonParser().parse(handler.toString()).asJsonObject.apply { addProperty("overlay", "map-two") }
        assertNotEquals(IconPixels.key(handler), IconPixels.key(other))
        handler.addProperty("item", "minecraft:stone")
        try { IconPixels.key(handler); fail("Ambiguous icon target must be rejected") } catch (_: IllegalArgumentException) { }
    }

    @Test fun httpServesBundledEditorAndRoutesDraftsWithMutationProtection() {
        var queries = 0; var exports = 0
        val server = EditorServer(0,DraftStore(temporary.newFolder()),{tool,args -> queries++; JsonObject().apply { addProperty("tool",tool); add("arguments",args) } },{byteArrayOf(1,2,3)},{start -> if(start) exports++; JsonObject().apply { addProperty("running",start) }})
        fun request(path: String, body: String? = null, header: Boolean = true, origin: String? = null): Pair<Int,String> {
            // HttpURLConnection silently drops Origin; send this case as real HTTP bytes.
            if (origin != null) return java.net.Socket("127.0.0.1",java.net.URI(server.address()).port).use { socket ->
                socket.soTimeout=3000
                val payload=body!!.toByteArray(Charsets.UTF_8)
                val headers="POST $path HTTP/1.1\r\nHost: ${java.net.URI(server.address()).authority}\r\nX-ForgeMCP: editor\r\nOrigin: $origin\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().write(headers.toByteArray(Charsets.UTF_8));socket.getOutputStream().write(payload)
                val reader=socket.getInputStream().bufferedReader(Charsets.UTF_8)
                reader.readLine().split(' ')[1].toInt() to reader.readText()
            }
            val connection = URL(server.address()+path).openConnection() as HttpURLConnection
            connection.connectTimeout=2000; connection.readTimeout=3000
            if(body!=null){connection.requestMethod="POST";connection.doOutput=true;if(header)connection.setRequestProperty("X-ForgeMCP","editor");if(origin!=null)connection.setRequestProperty("Origin",origin);connection.outputStream.use{it.write(body.toByteArray(Charsets.UTF_8))}}
            val status=connection.responseCode
            val text=(if(status<400)connection.inputStream else connection.errorStream).bufferedReader(Charsets.UTF_8).use{it.readText()}
            connection.disconnect();return status to text
        }
        try {
            assertTrue(server.start());assertFalse(server.start())
            val page=request("/");assertEquals(200,page.first);assertTrue(page.second.contains("RECIPE STUDIO"))
            assertEquals(200,request("/app.js").first)
            assertEquals(200,request("/board.js").first)
            assertEquals(200,request("/graph.js").first)
            assertEquals(200,request("/api/icon?item=minecraft%3Astone&v=${IconPixels.RENDER_VERSION}").first)
            assertEquals(400,request("/api/icon?item=minecraft%3Astone&v=1").first)
            assertEquals(400,request("/api/drafts",draft().toString(),header=false).first)
            assertEquals(400,request("/api/drafts",draft().toString(),origin="https://example.com").first)
            assertEquals(400,request("/api/query","""{"tool":"generate_recipe_code"}""").first)
            assertEquals(0,queries)
            val saved=JsonParser().parse(request("/api/drafts",draft().toString()).second).asJsonObject
            val id=saved.get("id").asString
            assertEquals(saved,JsonParser().parse(request("/api/drafts/$id").second))
            assertEquals(1,JsonParser().parse(request("/api/drafts").second).asJsonObject.getAsJsonArray("results").size())
            assertEquals(200,request("/api/query","""{"tool":"search_items","arguments":{"query":"iron"}}""").first)
            assertEquals(1,queries)
            assertEquals(200,request("/api/query","""{"tool":"get_recipes","arguments":{"item":"minecraft:stone"}}""").first)
            assertEquals(2,queries)
            assertEquals(200,request("/api/export","{}").first);assertEquals(1,exports)
            assertEquals(404,request("/does-not-exist").first)
        } finally {server.stop()}
    }
}
