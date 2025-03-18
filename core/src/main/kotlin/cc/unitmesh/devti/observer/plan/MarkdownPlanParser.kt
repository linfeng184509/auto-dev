package cc.unitmesh.devti.observer.plan

import com.intellij.openapi.diagnostic.logger
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.accept
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.ast.visitors.RecursiveVisitor
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/**
 * Markdown计划解析器，负责将markdown格式的计划文本解析为结构化的执行计划
 *
 * 示例：
 *
 * ```markdown
 * 1. 领域模型重构：
 *   - [✓] 将BlogPost实体合并到Blog聚合根，建立完整的领域对象
 *   - [*] 添加领域行为方法（发布、审核、评论等）
 *   - [!] 修复数据模型冲突
 *   - [ ] 实现新增的领域服务
 * 2. 分层结构调整：
 *   - [ ] 清理entity层冗余对象
 * ```
 */
object MarkdownPlanParser {
    private val logger = logger<MarkdownPlanParser>()

    private val markdownRootType = IElementType("ROOT")
    private val markdownFlavor = GFMFlavourDescriptor()

    private object TaskMarkers {
        val CHECKMARK = "✓"
        val COMPLETED = listOf("x", "X", "✓")
        val FAILED = listOf("!")
        val IN_PROGRESS = listOf("*")

        fun determineStatus(marker: String): TaskStatus = when (marker) {
            in COMPLETED -> TaskStatus.COMPLETED
            in FAILED -> TaskStatus.FAILED
            in IN_PROGRESS -> TaskStatus.IN_PROGRESS
            else -> TaskStatus.TODO
        }

        fun isCompleted(marker: String): Boolean = marker in COMPLETED
    }

    private object PatternMatcher {
        val TASK_PATTERN = Regex("^\\s*-\\s*\\[\\s*([xX!*✓]?)\\s*\\]\\s*(.*)")
        val SECTION_PATTERN = Regex("^(\\d+)\\.\\s*(?:\\[([xX!*✓]?)\\]\\s*)?(.+?)(?:\\s*\\[([xX!*✓]?)\\])?$")
        val UNORDERED_ITEM_CLEANER = Regex("^[\\-\\*]\\s+")
    }

    fun interpretPlan(content: String): List<AgentTaskEntry> {
        try {
            val documentTree = parseMarkdownDocument(content)
            return extractPlanStructure(documentTree, content)
        } catch (e: Exception) {
            logger.warn("无法解析markdown计划内容", e)
            return emptyList()
        }
    }


    private fun parseMarkdownDocument(content: String): ASTNode {
        return MarkdownParser(markdownFlavor).parse(markdownRootType, content)
    }

    private fun extractPlanStructure(documentNode: ASTNode, content: String): List<AgentTaskEntry> {
        val planSections = mutableListOf<AgentTaskEntry>()
        val topLevelSections = findTopLevelSections(documentNode)

        if (topLevelSections.isNotEmpty()) {
            if (isSimplePlanStructure(topLevelSections.first(), content)) {
                interpretSimplePlanStructure(topLevelSections.first(), content, planSections)
            } else {
                interpretDetailedPlanStructure(documentNode, content, planSections)
            }
        }

        return planSections
    }

    private fun findTopLevelSections(documentNode: ASTNode): List<ASTNode> {
        return documentNode.children.filter { it.type == MarkdownElementTypes.ORDERED_LIST }
    }

    private fun isSimplePlanStructure(sectionNode: ASTNode, content: String): Boolean {
        return sectionNode.children.none { listItem ->
            listItem.type == MarkdownElementTypes.LIST_ITEM &&
                    listItem.children.any { it.type == MarkdownElementTypes.UNORDERED_LIST }
        }
    }

    private fun interpretSimplePlanStructure(
        sectionListNode: ASTNode,
        content: String,
        planSections: MutableList<AgentTaskEntry>
    ) {
        val sectionVisitor = PlanSectionVisitor(content)
        sectionListNode.children.forEach { item ->
            if (item.type == MarkdownElementTypes.LIST_ITEM) {
                val sectionInfo = sectionVisitor.extractBasicSectionInfo(item)
                if (sectionInfo != null) {
                    planSections.add(AgentTaskEntry(sectionInfo.title, emptyList(), sectionInfo.completed))
                }
            }
        }
    }

    private fun interpretDetailedPlanStructure(
        documentNode: ASTNode,
        content: String,
        planSections: MutableList<AgentTaskEntry>
    ) {
        val planStructureInterpreter = DetailedPlanStructureInterpreter(content)
        planStructureInterpreter.interpretDocument(documentNode, planSections)
    }

    private class PlanSectionVisitor(private val documentContent: String) {
        fun extractBasicSectionInfo(sectionNode: ASTNode): BasicSectionInfo? {
            val sectionText = sectionNode.getTextInNode(documentContent).toString().trim()
            val titleMatch = "^(\\d+)\\.\\s*(.+?)(?:\\s*${TaskMarkers.CHECKMARK})?$".toRegex().find(sectionText)

            return titleMatch?.let {
                val title = it.groupValues[2].trim()
                val completed = sectionText.contains(TaskMarkers.CHECKMARK)
                BasicSectionInfo(title, completed)
            }
        }

        fun extractDetailedSectionInfo(listItemNode: ASTNode): DetailedSectionInfo? {
            val fullText = listItemNode.getTextInNode(documentContent).toString().trim()
            val firstLineEnd = fullText.indexOf('\n')
            val headerLine = if (firstLineEnd > 0) fullText.substring(0, firstLineEnd).trim() else fullText

            val headerMatch = PatternMatcher.SECTION_PATTERN.find(headerLine) ?: return null

            val title = headerMatch.groupValues[3].trim()

            val startMarker = headerMatch.groupValues[2]
            val endMarker = headerMatch.groupValues[4]
            val statusMarker = if (startMarker.isNotEmpty()) startMarker else endMarker

            val completed = TaskMarkers.isCompleted(statusMarker)
            val status = TaskMarkers.determineStatus(statusMarker)

            return DetailedSectionInfo(title, completed, status)
        }
    }

    private class DetailedPlanStructureInterpreter(private val documentContent: String) {
        private val sectionVisitor = PlanSectionVisitor(documentContent)
        private val taskExtractor = TaskExtractor(documentContent)
        private var currentSection: MutableSectionContext? = null

        fun interpretDocument(documentNode: ASTNode, planSections: MutableList<AgentTaskEntry>) {
            documentNode.accept(object : RecursiveVisitor() {
                override fun visitNode(node: ASTNode) {
                    when (node.type) {
                        MarkdownElementTypes.ORDERED_LIST -> {
                            processOrderedList(node, planSections)
                        }

                        MarkdownElementTypes.UNORDERED_LIST -> {
                            processUnorderedList(node)
                        }

                        else -> super.visitNode(node)
                    }
                }
            })

            finalizeCurrentSection(planSections)
        }

        private fun processOrderedList(listNode: ASTNode, planSections: MutableList<AgentTaskEntry>) {
            listNode.children.forEach { item ->
                if (item.type == MarkdownElementTypes.LIST_ITEM) {
                    val sectionInfo = sectionVisitor.extractDetailedSectionInfo(item)

                    if (sectionInfo != null) {
                        finalizeCurrentSection(planSections)
                        currentSection = MutableSectionContext(
                            title = sectionInfo.title,
                            completed = sectionInfo.completed,
                            status = sectionInfo.status
                        )
                        item.children.forEach { childNode ->
                            if (childNode.type == MarkdownElementTypes.UNORDERED_LIST) {
                                processUnorderedList(childNode)
                            }
                        }
                    }
                }
            }
        }

        private fun processUnorderedList(listNode: ASTNode) {
            currentSection?.let { section ->
                taskExtractor.extractTasks(listNode, section.tasks)
            }
        }

        private fun finalizeCurrentSection(planSections: MutableList<AgentTaskEntry>) {
            currentSection?.let { section ->
                val plan = AgentTaskEntry(
                    section.title,
                    section.tasks,
                    section.completed,
                    section.status
                )
                plan.updateCompletionStatus()
                planSections.add(plan)
                currentSection = null
            }
        }
    }

    private class TaskExtractor(private val documentContent: String) {
        fun extractTasks(listNode: ASTNode, taskList: MutableList<AgentPlanStep>) {
            listNode.children.forEach { item ->
                if (item.type != MarkdownElementTypes.LIST_ITEM) return@forEach

                val taskText = item.getTextInNode(documentContent).toString().trim()
                val firstLineEnd = taskText.indexOf('\n')
                val taskLine = if (firstLineEnd > 0) taskText.substring(0, firstLineEnd).trim() else taskText

                val taskMatch = PatternMatcher.TASK_PATTERN.find(taskLine)
                if (taskMatch != null) {
                    val statusMarker = taskMatch.groupValues[1]
                    val description = taskMatch.groupValues[2].trim()
                    val task = AgentPlanStep(
                        description,
                        TaskMarkers.isCompleted(statusMarker),
                        TaskMarkers.determineStatus(statusMarker)
                    )

                    taskList.add(task)
                } else {
                    val cleanDescription = taskLine.replace(PatternMatcher.UNORDERED_ITEM_CLEANER, "").trim()
                    if (cleanDescription.isNotEmpty()) {
                        taskList.add(AgentPlanStep(cleanDescription, false, TaskStatus.TODO))
                    }
                }
                item.children.forEach { childNode ->
                    if (childNode.type == MarkdownElementTypes.UNORDERED_LIST) {
                        extractTasks(childNode, taskList)
                    }
                }
            }
        }
    }

    fun parse(content: String): List<AgentTaskEntry> = interpretPlan(content)
    fun formatPlanToMarkdown(entries: MutableList<AgentTaskEntry>): String {
        val stringBuilder = StringBuilder()
        entries.forEachIndexed { index, entry ->
            stringBuilder.append("${index + 1}. ${entry.title}\n")
            entry.steps.forEach { step ->
                stringBuilder.append("   - [${if (step.completed) "x" else " "}] ${step.step}\n")
            }
        }
        return stringBuilder.toString()
    }
}


data class MutableSectionContext(
    val title: String,
    var completed: Boolean,
    var status: TaskStatus,
    val tasks: MutableList<AgentPlanStep> = mutableListOf()
)

data class BasicSectionInfo(
    val title: String,
    val completed: Boolean
)

data class DetailedSectionInfo(
    val title: String,
    val completed: Boolean,
    val status: TaskStatus
)
