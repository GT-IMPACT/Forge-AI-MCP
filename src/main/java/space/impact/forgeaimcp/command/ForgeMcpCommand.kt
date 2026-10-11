package space.impact.forgeaimcp.command

import net.minecraft.command.CommandBase
import net.minecraft.command.ICommandSender
import net.minecraft.command.WrongUsageException
import net.minecraft.util.ChatComponentText
import net.minecraft.client.Minecraft
import net.minecraft.event.ClickEvent
import space.impact.forgeaimcp.config.McpConfig
import space.impact.forgeaimcp.mcp.McpInstallation
import space.impact.forgeaimcp.mcp.McpRuntime

class ForgeMcpCommand : CommandBase() {
    override fun getCommandName() = "forgemcp"
    override fun getCommandUsage(sender: ICommandSender) = "/forgemcp <start|stop|status|config>"
    override fun getRequiredPermissionLevel() = 0
    override fun canCommandSenderUseCommand(sender: ICommandSender) = true

    override fun processCommand(sender: ICommandSender, args: Array<String>) {
        if (args.size != 1) throw WrongUsageException(getCommandUsage(sender))
        val message = try {
            when (args[0]) {
                "start" -> if (McpRuntime.start()) "ForgeMCP запущен: ${McpRuntime.address()}. Подключение: /forgemcp config"
                    else "ForgeMCP уже запущен: ${McpRuntime.address()}. Подключение: /forgemcp config"
                "stop" -> if (McpRuntime.stop()) "ForgeMCP остановлен." else "ForgeMCP уже выключен."
                "status" -> if (McpRuntime.isRunning()) "ForgeMCP работает: ${McpRuntime.address()}"
                    else "ForgeMCP выключен. Запуск: /forgemcp start"
                "config" -> {
                    val directory = McpInstallation.install(Minecraft.getMinecraft().mcDataDir, McpConfig.port)
                    val link = ChatComponentText("ForgeMCP: открыть папку подключения ($directory)")
                    link.chatStyle.chatClickEvent = ClickEvent(ClickEvent.Action.OPEN_FILE, directory.absolutePath)
                    link.chatStyle.underlined = true
                    sender.addChatMessage(link)
                    "Готовые настройки: mcp.json; для TOML: codex.toml. Инструкция: README.txt"
                }
                else -> throw WrongUsageException(getCommandUsage(sender))
            }
        } catch (e: WrongUsageException) {
            throw e
        } catch (e: Exception) {
            "ForgeMCP: ${e.message ?: e.javaClass.simpleName}"
        }
        sender.addChatMessage(ChatComponentText(message))
    }

    override fun addTabCompletionOptions(sender: ICommandSender, args: Array<String>): List<String>? =
        if (args.size == 1) getListOfStringsMatchingLastWord(args, "start", "stop", "status", "config") else null
}
