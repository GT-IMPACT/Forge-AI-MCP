package space.impact.forgeaimcp.mcp

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Installs only our generated files in the game directory. Does not start a process or listener. */
object McpInstallation {
    fun install(gameDirectory: File, port: Int): File {
        val directory = File(gameDirectory, "forgemcp").canonicalFile
        Files.createDirectories(directory.toPath())
        val jar = File(directory, "forgemcp-stdio.jar")
        val bundled = McpInstallation::class.java.getResourceAsStream("/assets/forgeaimcp/mcp/forgemcp-stdio.bin")
            ?: error("В моде отсутствует forgemcp-stdio.jar. Переустановите JAR мода.")
        writeIfChanged(jar, bundled.use { it.readBytes() })
        val instructions = McpInstallation::class.java.getResourceAsStream("/assets/forgeaimcp/mcp/README.txt")
            ?: error("В моде отсутствует инструкция подключения.")
        writeIfChanged(File(directory, "README.txt"), instructions.use { it.readBytes() })
        val managed = mapOf(
            "skills/gt-impact-recipes/SKILL.md" to "/assets/forgeaimcp/mcp/skills/gt-impact-recipes/SKILL.md",
            "skills/gt-impact-recipes/references/authoring.md" to "/assets/forgeaimcp/mcp/skills/gt-impact-recipes/references/authoring.md",
            "editor/index.html" to "/assets/forgeaimcp/editor/index.html",
            "editor/app.js" to "/assets/forgeaimcp/editor/app.js",
            "editor/style.css" to "/assets/forgeaimcp/editor/style.css",
        )
        for ((relative, path) in managed) {
            val resource = McpInstallation::class.java.getResourceAsStream(path)
                ?: error("В моде отсутствует $relative")
            val target = File(directory, relative)
            Files.createDirectories(target.parentFile.toPath())
            writeIfChanged(target, resource.use { it.readBytes() })
        }

        val javaDirectory = File(System.getProperty("java.home"), "bin")
        val java = listOf(File(javaDirectory, "java.exe"), File(javaDirectory, "java"))
            .firstOrNull { it.isFile }?.canonicalFile
            ?: error("Не найден исполняемый файл Java в $javaDirectory")
        val args = JsonArray().apply {
            listOf("-jar", jar.absolutePath, "--port", port.toString()).forEach { add(JsonPrimitive(it)) }
        }
        val server = JsonObject().apply { addProperty("command", java.absolutePath); add("args", args) }
        val config = JsonObject().apply {
            add("mcpServers", JsonObject().apply { add("forgemcp", server) })
        }
        val gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
        writeIfChanged(File(directory, "mcp.json"), (gson.toJson(config) + "\n").toByteArray(Charsets.UTF_8))
        val toml = "[mcp_servers.forgemcp]\ncommand = ${gson.toJson(java.absolutePath)}\nargs = $args\n"
        writeIfChanged(File(directory, "codex.toml"), toml.toByteArray(Charsets.UTF_8))
        return directory
    }

    private fun writeIfChanged(file: File, bytes: ByteArray) {
        if (file.isFile && file.readBytes().contentEquals(bytes)) return
        val temporary = Files.createTempFile(file.parentFile.toPath(), ".forgemcp-", ".tmp")
        try {
            Files.write(temporary, bytes)
            try {
                Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
