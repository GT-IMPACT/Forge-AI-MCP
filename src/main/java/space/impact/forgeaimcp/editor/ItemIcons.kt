package space.impact.forgeaimcp.editor

import codechicken.nei.ItemList
import codechicken.nei.guihook.GuiContainerManager
import com.google.gson.*
import cpw.mods.fml.common.Loader
import cpw.mods.fml.common.eventhandler.SubscribeEvent
import cpw.mods.fml.common.gameevent.TickEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.OpenGlHelper
import net.minecraft.client.renderer.RenderHelper
import net.minecraft.client.shader.Framebuffer
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.EXTFramebufferObject
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import space.impact.forgeaimcp.adapter.JsonSupport
import space.impact.forgeaimcp.mcp.GameData
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

object IconPixels {
    const val RENDER_VERSION = 3
    const val SIZE = 128
    fun cacheNamespace(profile: JsonObject, renderVersion: Int = RENDER_VERSION): String =
        JsonSupport.hash(JsonParser().parse(profile.toString()).asJsonObject.apply {
            addProperty("iconRendererVersion", renderVersion)
        }).take(24)
    /** OpenGL is bottom-up RGBA; PNG is top-down ARGB. */
    fun image(bytes: ByteBuffer, size: Int): BufferedImage = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).apply {
        for (y in 0 until size) for (x in 0 until size) {
            val offset = ((size - y - 1) * size + x) * 4
            val red = bytes.get(offset).toInt() and 255
            val green = bytes.get(offset + 1).toInt() and 255
            val blue = bytes.get(offset + 2).toInt() and 255
            val alpha = bytes.get(offset + 3).toInt() and 255
            setRGB(x, y, (alpha shl 24) or (red shl 16) or (green shl 8) or blue)
        }
    }
    fun key(args: JsonObject): String = JsonSupport.hash(JsonObject().apply {
        addProperty("item", JsonSupport.text(args, "item")); addProperty("metadata", JsonSupport.int(args, "metadata"))
        add("nbt", args.get("nbt") ?: JsonNull.INSTANCE)
    })
}

/** All rendering and native stack access happens during RenderTick END, never in HTTP workers. */
object ItemIcons {
    private data class Request(val key: String, val args: JsonObject, val result: CompletableFuture<File>)
    private val pending = ConcurrentLinkedQueue<Request>()
    private val requests = ConcurrentHashMap<String, CompletableFuture<File>>()
    private var directory: File? = null
    private var frame: Framebuffer? = null
    private var exportItems: List<ItemStack> = emptyList()
    private var cursor = 0
    private var failures = 0
    private var exportActive = false
    private var catalog: java.io.BufferedWriter? = null
    private var lastError: String? = null
    private var exportRoot: File? = null
    @Volatile private var deleteFrameRequested = false

    @Synchronized fun configure(root: File) {
        exportRoot = File(root, "export").apply { mkdirs() }
        val profile = JsonObject().apply {
            add("mods", JsonSupport.strings(Loader.instance().modList.map { "${it.modId}:${it.version}:${it.source?.lastModified()}" }.sorted()))
            add("resourcePacks", JsonSupport.strings(Minecraft.getMinecraft().gameSettings.resourcePacks.map { it.toString() + ":" + File(Minecraft.getMinecraft().mcDataDir, "resourcepacks/$it").lastModified() }))
        }
        directory = File(exportRoot, "icons/${IconPixels.cacheNamespace(profile)}").apply { mkdirs() }
        profile.addProperty("iconRendererVersion", IconPixels.RENDER_VERSION)
        File(exportRoot, "profile.json").writeText(profile.toString(), Charsets.UTF_8)
    }
    fun png(args: JsonObject): ByteArray {
        val key = IconPixels.key(args)
        val folder = directory ?: error("Open /forgemcp ui first")
        val target = File(folder, "$key.png")
        if (target.isFile) return target.readBytes()
        require(pending.size < 256) { "Icon queue full; retry shortly" }
        val created = CompletableFuture<File>()
        val future = requests.putIfAbsent(key, created) ?: created.also {
            pending.add(Request(key, JsonParser().parse(args.toString()).asJsonObject, it))
        }
        return future.get(20, TimeUnit.SECONDS).readBytes()
    }
    /** Called on the game thread. Does not hold the HTTP worker while exporting. */
    @Synchronized fun export(start: Boolean): JsonObject {
        if (start && !exportActive) {
            check(ItemList.loadFinished) { "NEI is still loading" }
            check(OpenGlHelper.isFramebufferEnabled()) { "Enable framebuffer objects (FBO) in video settings to export icons" }
            exportItems = ItemList.items.toList(); cursor = 0; failures = 0; lastError = null
            catalog?.close()
            catalog = File(exportRoot ?: error("Editor not started"), "catalog.ndjson.tmp").bufferedWriter(Charsets.UTF_8)
            exportActive = true
        }
        return JsonObject().apply {
            addProperty("running", exportActive); addProperty("total", exportItems.size)
            addProperty("processed", cursor); addProperty("failed", failures)
            addProperty("directory", exportRoot?.absolutePath); addProperty("lastError", lastError)
        }
    }
    @Synchronized fun stop() {
        exportActive = false; runCatching { catalog?.close() }; catalog = null
        pending.forEach { it.result.completeExceptionally(IllegalStateException("Editor stopped")) }
        pending.clear(); requests.clear()
        directory = null
        deleteFrameRequested = true
    }
    @SubscribeEvent @Synchronized fun renderTick(event: TickEvent.RenderTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        if (deleteFrameRequested) { frame?.deleteFramebuffer(); frame = null; deleteFrameRequested = false }
        if (directory == null) return
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(8)
        var processed = 0
        while (processed < 8 && System.nanoTime() < deadline) {
            val request = pending.poll()
            if (request != null) {
                try { request.result.complete(writeIcon(request.key, GameData.resolveItem(request.args))) }
                catch (e: Exception) { request.result.completeExceptionally(e) }
                finally { requests.remove(request.key, request.result) }
            } else if (exportActive && cursor < exportItems.size) {
                val stack = exportItems[cursor++]
                try {
                    var failed = false
                    val data = try { GameData.describeItem(stack) } catch (e: Exception) {
                        failed = true; lastError = e.message
                        JsonObject().apply {
                            addProperty("item", Item.itemRegistry.getNameForObject(stack.item))
                            addProperty("metadata", stack.itemDamage); addProperty("count", stack.stackSize)
                            add("nbt", stack.tagCompound?.let { JsonPrimitive(it.toString()) } ?: JsonNull.INSTANCE)
                            addProperty("detailsError", e.message)
                        }
                    }
                    val key = IconPixels.key(data)
                    try {
                        val file = writeIcon(key, stack)
                        data.addProperty("icon", "icons/${directory!!.name}/${file.name}")
                    } catch (e: Exception) {
                        failed = true; lastError = e.message
                        data.add("icon", JsonNull.INSTANCE); data.addProperty("iconError", e.message)
                    }
                    if (failed) failures++
                    catalog!!.write(data.toString()); catalog!!.newLine()
                } catch (e: Exception) { failures++; lastError = e.message; finishExport() }
                if (exportActive && cursor == exportItems.size) finishExport()
            } else {
                if (exportActive) finishExport()
                break
            }
            processed++
        }
    }
    private fun finishExport() {
        exportActive = false
        try { catalog?.close() } catch (e: Exception) { failures++; lastError = e.message }
        catalog = null
        val root = exportRoot ?: return
        try {
            java.nio.file.Files.move(File(root, "catalog.ndjson.tmp").toPath(), File(root, "catalog.ndjson").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            File(root, "status.json").writeText(export(false).toString(), Charsets.UTF_8)
        } catch (e: Exception) { failures++; lastError = e.message }
    }
    private fun writeIcon(key: String, stack: ItemStack): File {
        val target = File(directory ?: error("Editor stopped"), "$key.png")
        if (target.isFile) return target
        val image = render(stack.copy().apply { stackSize = 1 })
        val temp = java.nio.file.Files.createTempFile(target.parentFile.toPath(), ".icon-", ".tmp")
        try {
            check(ImageIO.write(image, "PNG", temp.toFile())) { "PNG encoder unavailable" }
            java.nio.file.Files.move(temp, target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { java.nio.file.Files.deleteIfExists(temp) }
        return target
    }
    private fun render(stack: ItemStack): BufferedImage {
        check(OpenGlHelper.isFramebufferEnabled()) { "FBO disabled: enable framebuffer objects to render icons" }
        val mc = Minecraft.getMinecraft()
        val savedFramebuffer = GL11.glGetInteger(EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT)
        val savedMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE)
        val oldZ = GuiContainerManager.drawItems.zLevel
        val brightnessX = OpenGlHelper.lastBrightnessX; val brightnessY = OpenGlHelper.lastBrightnessY
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS)
        GL11.glPushClientAttrib(-1) // GL_CLIENT_ALL_ATTRIB_BITS
        GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glPushMatrix()
        GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPushMatrix()
        try {
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit)
            val buffer = frame ?: Framebuffer(IconPixels.SIZE, IconPixels.SIZE, true).also { frame = it; it.setFramebufferColor(0f, 0f, 0f, 0f) }
            GL11.glColorMask(true, true, true, true)
            GL11.glDepthMask(true)
            GL11.glDisable(GL11.GL_SCISSOR_TEST)
            buffer.framebufferClear(); buffer.bindFramebuffer(true)
            GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glLoadIdentity()
            GL11.glOrtho(0.0, 16.0, 16.0, 0.0, 1000.0, 3000.0)
            GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glLoadIdentity()
            GL11.glTranslatef(0f, 0f, -2000f)
            // Inventory icons must not inherit world fog/lightmap or unscaled block normals.
            mc.entityRenderer.disableLightmap(0.0)
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240f, 240f)
            GL11.glDisable(GL11.GL_FOG)
            GL11.glEnable(GL12.GL_RESCALE_NORMAL)
            GL11.glDepthFunc(GL11.GL_LEQUAL)
            GL11.glDisable(GL11.GL_SCISSOR_TEST); GL11.glEnable(GL11.GL_TEXTURE_2D)
            GL11.glColor4f(1f, 1f, 1f, 1f)
            RenderHelper.enableGUIStandardItemLighting()
            GuiContainerManager.drawItems.zLevel = 0f
            GuiContainerManager.drawItem(0, 0, stack, false, "")
            val pixels = BufferUtils.createByteBuffer(IconPixels.SIZE * IconPixels.SIZE * 4)
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1)
            GL11.glReadPixels(0, 0, IconPixels.SIZE, IconPixels.SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels)
            return IconPixels.image(pixels, IconPixels.SIZE)
        } finally {
            GuiContainerManager.drawItems.zLevel = oldZ
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, brightnessX, brightnessY)
            GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glPopMatrix()
            GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glPopMatrix()
            GL11.glMatrixMode(savedMode)
            OpenGlHelper.func_153171_g(OpenGlHelper.field_153198_e, savedFramebuffer)
            GL11.glPopClientAttrib(); GL11.glPopAttrib()
        }
    }
}
