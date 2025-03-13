package cc.unitmesh.devti.sketch

import cc.unitmesh.devti.devin.dataprovider.BuiltinCommand
import cc.unitmesh.devti.observer.agent.AgentStateService
import cc.unitmesh.devti.provider.devins.LanguageProcessor
import cc.unitmesh.devti.provider.toolchain.ToolchainFunctionProvider
import cc.unitmesh.devti.util.parser.CodeFence
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFileFactory
import com.intellij.sql.psi.SqlFile

@Service(Service.Level.PROJECT)
class AutoSketchMode(val project: Project) {
    var isEnable: Boolean = false

    fun start(text: String, listener: SketchInputListener) {
        val codeFenceList = CodeFence.parseAll(text)
        val devinCodeFence = codeFenceList.filter {
            it.language.displayName == "DevIn"
        }

        val commands: MutableList<BuiltinCommand> = mutableListOf()

        val allCode = devinCodeFence.filter {
            !it.text.contains("<DevinsError>") && (hasReadCommand(it) || hasToolchainFunctionCommand(it))
        }

        invokeLater {
            val language = CodeFence.findLanguage("DevIn") ?: return@invokeLater
            commands += devinCodeFence.mapNotNull {
                val psiFile = PsiFileFactory.getInstance(project).createFileFromText(language, it.text)
                    ?: return@mapNotNull null

                LanguageProcessor.devin()?.transpileCommand(project, psiFile) ?: emptyList()
            }.flatten()

            project.getService(AgentStateService::class.java).addTools(commands)
        }

        if (allCode.isEmpty()) return

        val allCodeText = allCode.map { it.text }.distinct().joinToString("\n")
        if (allCodeText.trim().isEmpty()) {
            logger<SketchToolWindow>().error("No code found")
        } else {
            listener.manualSend(allCodeText)
        }
    }

    private fun hasReadCommand(fence: CodeFence): Boolean = BuiltinCommand.READ_COMMANDS.any { command ->
        fence.text.contains("/" + command.commandName + ":")
    }

    private fun hasToolchainFunctionCommand(fence: CodeFence): Boolean {
        val toolchainCmds = ToolchainFunctionProvider.all().map { it.funcNames() }.flatten()
        return toolchainCmds.any {
            fence.text.contains("/$it:")
        }
    }

    companion object {
        fun getInstance(project: Project): AutoSketchMode {
            return project.service<AutoSketchMode>()
        }
    }
}