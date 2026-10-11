package space.impact.forgeaimcp.editor

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.net.Socket
import java.util.concurrent.CountDownLatch

/** Development preview against an already-running game. No icon rendering outside Minecraft. */
fun main() {
    val server = EditorServer(25578, DraftStore(File("build/editor-preview/drafts")), { tool, args ->
        try { Socket("127.0.0.1",25576).use { socket ->
            socket.soTimeout=15000
            val request=JsonObject().apply{addProperty("tool",tool);add("arguments",args)}
            socket.getOutputStream().bufferedWriter(Charsets.UTF_8).apply{write(request.toString());newLine();flush()}
            val reply=JsonParser().parse(socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine()).asJsonObject
            if(reply.has("error"))error(reply.get("error").asString)
            reply.getAsJsonObject("result")
        } } catch (_: java.io.IOException) { previewData(tool,args) }
    }, { args ->
        // Use real client-rendered pixels while previewing bundled UI changes without restarting the game.
        val params = listOf("item", "metadata", "nbt").filter { args.has(it) && !args.get(it).isJsonNull }
            .joinToString("&") { it + "=" + java.net.URLEncoder.encode(args.get(it).asString, "UTF-8") }
        val connection = java.net.URL("http://127.0.0.1:25577/api/icon?$params").openConnection()
        connection.connectTimeout = 2000; connection.readTimeout = 25000
        connection.getInputStream().use { it.readBytes() }
    }, { JsonObject().apply {addProperty("running",false);addProperty("total",0);addProperty("processed",0);addProperty("failed",0)} })
    server.start()
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    println("Editor development preview: ${server.address()}")
    CountDownLatch(1).await()
}

private fun previewData(tool:String,args:JsonObject):JsonObject {
    val items=JsonParser().parse("""[{"item":"appliedenergistics2:tile.BlockController","metadata":0,"count":1,"name":"ME Controller","nbt":null},{"item":"appliedenergistics2:tile.BlockEnergyAcceptor","metadata":0,"count":1,"name":"Energy Acceptor"},{"item":"appliedenergistics2:item.ItemMultiMaterial","metadata":24,"count":2,"name":"Engineering Processor"},{"item":"gregtech:gt.metaitem.02","metadata":19085,"count":4,"name":"Fine Platinum Wire"},{"item":"appliedenergistics2:item.ItemMultiPart","metadata":76,"count":4,"name":"ME Dense Smart Cable - Fluix"},{"item":"gregtech:gt.metaitem.01","metadata":18061,"count":4,"name":"Double Maraging Steel 300 Plate"},{"item":"gregtech:gt.metaitem.01","metadata":17028,"count":8,"name":"Titanium Plate"}]""").asJsonArray
    fun json(value:String)=JsonParser().parse(value).asJsonObject
    fun page(values:List<com.google.gson.JsonElement>):JsonObject {
        val offset=args.get("offset")?.asInt?:0;val limit=args.get("limit")?.asInt?:20
        return JsonObject().apply{add("results",com.google.gson.JsonArray().apply{values.drop(offset).take(limit).forEach{add(it)}});addProperty("total",values.size);addProperty("hasMore",offset+limit<values.size)}
    }
    return when(tool) {
        "get_status"->json("""{"running":false,"worldLoaded":false,"itemsReady":true,"itemCount":7,"developmentPreview":true}""")
        "list_adapters"->json("""{"results":[{"adapter":"gregtech-impact","available":true,"reason":"development fixture"},{"adapter":"minecraft","available":true,"reason":"development fixture"}]}""")
        "list_recipe_maps"->if(args.get("adapter")?.asString=="minecraft") json("""{"results":[{"id":"crafting-shaped","name":"Верстак — shaped","kind":"shaped"},{"id":"crafting-shapeless","name":"Верстак — shapeless","kind":"shapeless"},{"id":"furnace","name":"Печка","kind":"furnace"}],"hasMore":false}""") else json("""{"results":[{"id":"sAssemblerRecipes","mNEIName":"Assembler","kind":"machine"},{"id":"assembly-line","name":"Assembly Line","kind":"assembly-line"}],"hasMore":false}""")
        "search_items"->page(items.filter{it.toString().contains(args.get("query")?.asString?:"",ignoreCase=true)})
        "search_fluids"->page(listOf(json("""{"fluid":"oxygen","name":"Oxygen Gas","amount":1000,"unit":"mB"}""")))
        "search_oredict"->page(listOf(json("""{"name":"circuitData","itemCount":4}""")))
        "search_machine_recipes"->page(emptyList())
        "get_recipe_authoring_guide"->json("""{"text":"GT Impact — руководство доступно через MCP в запущенной игре."}""")
        else->error("Unsupported preview query: $tool")
    }
}
