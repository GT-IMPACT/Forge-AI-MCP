package space.impact.forgeaimcp.editor

import com.google.gson.*
import space.impact.forgeaimcp.adapter.JsonSupport
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** User-authored data only. Never generates source code or registers recipes. */
class DraftStore(private val directory: File) {
    companion object { const val MAX_BYTES = 262144 }
    private fun file(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}"))) { "Invalid draft ID" }
        return File(directory, "$id.json")
    }
    @Synchronized fun list(): JsonObject = JsonObject().apply {
        add("results", JsonArray().apply {
            directory.listFiles()?.filter { it.isFile && it.extension == "json" }?.sortedByDescending { it.lastModified() }?.forEach { entry ->
                try {
                    val draft = load(entry.nameWithoutExtension)
                    add(JsonObject().apply {
                        for (key in listOf("id", "title", "adapter", "map", "kind", "updatedAt", "itemInputs", "itemOutputs", "fluidInputs", "fluidOutputs")) if (draft.has(key)) add(key, draft.get(key))
                    })
                } catch (_: Exception) { /* An unrelated or damaged file is not an editable draft. */ }
            }
        })
    }
    @Synchronized fun load(id: String): JsonObject {
        val target = file(id)
        require(target.isFile) { "Draft not found: $id" }
        require(target.length() <= MAX_BYTES) { "Draft is too large" }
        val draft = JsonParser().parse(target.readText(Charsets.UTF_8)).asJsonObject
        validate(draft)
        require(draft.get("id")?.asString == id) { "Draft ID does not match its filename" }
        return draft
    }
    @Synchronized fun save(value: JsonObject): JsonObject {
        require(value.toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Draft is too large" }
        val draft = JsonParser().parse(value.toString()).asJsonObject
        validate(draft)
        val id = draft.get("id")?.takeUnless { it.isJsonNull || it.asString.isBlank() }?.asString ?: UUID.randomUUID().toString()
        val target = file(id)
        draft.addProperty("id", id)
        draft.addProperty("updatedAt", System.currentTimeMillis())
        Files.createDirectories(directory.toPath())
        val temp = Files.createTempFile(directory.toPath(), ".draft-", ".tmp")
        try {
            Files.write(temp, GsonBuilder().setPrettyPrinting().create().toJson(draft).toByteArray(Charsets.UTF_8))
            try { Files.move(temp, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temp) }
        return draft
    }
    private fun validate(draft: JsonObject) {
        require(JsonSupport.int(draft, "schemaVersion") == 1) { "Expected draft schemaVersion=1" }
        require(JsonSupport.text(draft, "title").length in 1..200) { "Title must contain 1..200 characters" }
        require(JsonSupport.text(draft, "adapter").matches(Regex("[a-z0-9][a-z0-9-]*"))) { "Explicit adapter ID required" }
        require(JsonSupport.text(draft, "map").length <= 400) { "Map ID too long" }
        val kind = JsonSupport.text(draft, "kind")
        require(kind in listOf("machine", "assembly-line", "shaped", "shapeless", "furnace")) { "Invalid recipe kind" }
        if (kind == "shaped") {
            val grid = draft.getAsJsonObject("grid") ?: error("Shaped recipe needs a grid")
            val width = JsonSupport.int(grid, "width"); val height = JsonSupport.int(grid, "height")
            require(width in 1..3 && height in 1..3) { "Grid must fit 3x3" }
            val cells = grid.getAsJsonArray("cells") ?: error("Grid cells required")
            require(cells.size() == width * height) { "Grid size mismatch" }
            val slots = JsonSupport.array(draft, "itemInputs").size()
            val used = mutableSetOf<Int>()
            for (cell in cells) if (!cell.isJsonNull) {
                require(cell.isJsonPrimitive && cell.asJsonPrimitive.isNumber)
                val index = cell.asBigDecimal.intValueExact()
                require(index in 0 until slots && used.add(index)) { "Grid index invalid or repeated; each occupied cell needs its own input slot" }
            }
            require(used.size == slots) { "Every input must appear in grid" }
            JsonSupport.bool(grid, "mirrored", true)
        }
        if (draft.has("experience")) {
            val xp = draft.get("experience")
            require(xp.isJsonPrimitive && xp.asJsonPrimitive.isNumber && xp.asDouble.isFinite() && xp.asDouble >= 0) { "Invalid furnace experience" }
        }
        if (draft.has("copiesInputNbt")) JsonSupport.bool(draft, "copiesInputNbt")
        for (key in listOf("durationTicks", "eut")) require(JsonSupport.int(draft, key) >= 0) { "$key must be nonnegative" }
        JsonSupport.int(draft, "specialValue")
        for (key in listOf("itemInputs", "itemOutputs", "fluidInputs", "fluidOutputs", "specialItems")) {
            if (!draft.has(key)) continue
            require(draft.get(key).isJsonArray && draft.getAsJsonArray(key).size() <= 64) { "$key must be an array of at most 64 slots" }
            for (slot in draft.getAsJsonArray(key)) {
                require(slot.isJsonObject) { "$key slot must be an object" }
                val obj = slot.asJsonObject
                if (key.startsWith("fluid")) {
                    require(JsonSupport.text(obj, "fluid").isNotBlank()) { "Fluid ID required" }
                    require(JsonSupport.int(obj, "amount") > 0) { "Fluid amount must be positive" }
                } else {
                    validateItem(obj, key != "itemOutputs")
                    if (key == "itemOutputs") require(JsonSupport.int(obj, "chance", 10000) in 1..10000) { "Chance must be 1..10000" }
                    if (obj.has("alternatives")) {
                        require(key == "itemInputs" && obj.get("alternatives").isJsonArray && obj.getAsJsonArray("alternatives").size() in 1..100) { "Alternatives are input slots only, at most 100" }
                        obj.getAsJsonArray("alternatives").forEach { require(it.isJsonObject); validateItem(it.asJsonObject, true) }
                    }
                }
            }
        }
        if (draft.has("research") && !draft.get("research").isJsonNull) {
            val research = draft.getAsJsonObject("research")
            require(JsonSupport.int(research, "timeTicks") >= 0)
            if (research.has("item") && !research.get("item").isJsonNull) validateItem(research.getAsJsonObject("item"), false)
        }
        if (draft.has("notes")) require(JsonSupport.text(draft, "notes").length <= 8000) { "Notes too long" }
    }
    private fun validateItem(obj: JsonObject, input: Boolean) {
        if (obj.has("oreDictionary")) require(JsonSupport.text(obj, "oreDictionary").isNotBlank())
        else require(JsonSupport.text(obj, "item").matches(Regex("[a-zA-Z0-9_.-]+:[a-zA-Z0-9_./-]+"))) { "Registry item ID required" }
        require(JsonSupport.int(obj, "metadata") in 0..32767) { "Invalid metadata" }
        require(JsonSupport.int(obj, "count", 1) >= if (input) 0 else 1) { "Invalid item count" }
        if (obj.has("nbt") && !obj.get("nbt").isJsonNull) JsonSupport.text(obj, "nbt")
    }
}
