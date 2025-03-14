package cc.unitmesh.devti.observer.agent

import cc.unitmesh.devti.agent.tool.AgentTool
import cc.unitmesh.devti.llms.custom.Message
import com.intellij.util.diff.Diff.Change
import java.util.UUID

data class AgentState(
    /**
     * First question of user
     */
    var originIntention: String = "",

    var conversationId: String = UUID.randomUUID().toString(),

    var changeList: List<Change> = emptyList(),

    var messages: List<Message> = emptyList(),

    var usedTools: List<AgentTool> = emptyList(),

    /**
     * Logging environment variables, maybe related to  [cc.unitmesh.devti.provider.context.ChatContextProvider]
     */
    var environment: Map<String, String> = emptyMap()
)

