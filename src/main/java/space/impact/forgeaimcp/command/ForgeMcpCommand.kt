package space.impact.forgeaimcp.command

import net.minecraft.command.CommandBase
import net.minecraft.command.ICommandSender
import net.minecraft.command.WrongUsageException
import net.minecraft.util.ChatComponentText
import space.impact.forgeaimcp.mcp.McpRuntime

class ForgeMcpCommand : CommandBase() {
    override fun getCommandName() = "forgemcp"
    override fun getCommandUsage(sender: ICommandSender) = "/forgemcp <start|stop|status>"
    override fun getRequiredPermissionLevel() = 0
    override fun canCommandSenderUseCommand(sender: ICommandSender) = true

    override fun processCommand(sender: ICommandSender, args: Array<String>) {
        if (args.size != 1) throw WrongUsageException(getCommandUsage(sender))
        val message = try {
            when (args[0]) {
                "start" -> if (McpRuntime.start()) "ForgeMCP запущен: ${McpRuntime.address()}"
                    else "ForgeMCP уже запущен: ${McpRuntime.address()}"
                "stop" -> if (McpRuntime.stop()) "ForgeMCP остановлен." else "ForgeMCP уже выключен."
                "status" -> if (McpRuntime.isRunning()) "ForgeMCP работает: ${McpRuntime.address()}"
                    else "ForgeMCP выключен. Запуск: /forgemcp start"
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
        if (args.size == 1) getListOfStringsMatchingLastWord(args, "start", "stop", "status") else null
}
