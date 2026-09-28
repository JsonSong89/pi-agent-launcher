package com.piagent.launcher.conversations

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.piagent.launcher.services.PiFileWatcher
import com.piagent.launcher.services.PiStatusWidget
import com.piagent.launcher.services.PiTerminalService
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Project-level conversation map. One conversation <-> one Pi terminal.
 * Switching never kills other sessions; delete closes the terminal and drops history.
 */
@Service(Service.Level.PROJECT)
class PiConversationService(private val project: Project) {

    fun interface Listener {
        fun onChanged(event: ChangeEvent)
    }

    enum class ChangeKind {
        STRUCTURE,
        DRAFT_APPENDED,
        SENT
    }

    data class ChangeEvent(
        val kind: ChangeKind,
        val conversationId: String? = null
    )

    private val conversations = LinkedHashMap<String, PiConversation>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val titleSeq = AtomicInteger(1)
    @Volatile
    private var activeId: String? = null

    init {
        val terminal = terminal()
        terminal.addListener(object : PiTerminalService.SessionListener {
            override fun onSessionEnded(conversationId: String) {
                notifyListeners(ChangeEvent(ChangeKind.STRUCTURE, conversationId))
                PiStatusWidget.update(project)
            }

            override fun onSessionStarted(conversationId: String) {
                notifyListeners(ChangeEvent(ChangeKind.STRUCTURE, conversationId))
                PiStatusWidget.update(project)
            }
        })
        terminal.onRestartRequested = { conversationId, tabName ->
            if (get(conversationId) != null) {
                terminal.launch(conversationId, tabName)
            }
        }
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun all(): List<PiConversation> = synchronized(conversations) { conversations.values.toList() }

    fun active(): PiConversation? = synchronized(conversations) {
        activeId?.let { conversations[it] }
    }

    fun get(id: String): PiConversation? = synchronized(conversations) { conversations[id] }

    fun createConversation(): PiConversation {
        val n = titleSeq.getAndIncrement()
        val title = "Pi-$n-${LocalDateTime.now().format(TITLE_TIME_FORMAT)}"
        val conversation = PiConversation(
            id = UUID.randomUUID().toString(),
            title = title,
            tabName = title
        )
        synchronized(conversations) {
            conversations[conversation.id] = conversation
            activeId = conversation.id
        }
        terminal().launch(conversation.id, conversation.tabName)
        PiFileWatcher.getInstance(project).startWatching()
        PiStatusWidget.update(project)
        notifyListeners(ChangeEvent(ChangeKind.STRUCTURE, conversation.id))
        return conversation
    }

    fun ensureActiveConversation(): PiConversation {
        return active() ?: createConversation()
    }

    fun setActive(id: String) {
        val conversation = synchronized(conversations) {
            if (!conversations.containsKey(id) || activeId == id) return
            activeId = id
            conversations[id]
        } ?: return
        terminal().selectTab(conversation.id, requestFocus = false)
        notifyListeners(ChangeEvent(ChangeKind.STRUCTURE, id))
    }

    fun deleteConversation(id: String) {
        val removed = synchronized(conversations) {
            val conversation = conversations.remove(id) ?: return
            if (activeId == id) {
                activeId = conversations.keys.lastOrNull()
            }
            conversation
        }
        terminal().close(removed.id)
        active()?.let { terminal().selectTab(it.id, requestFocus = false) }
        PiStatusWidget.update(project)
        notifyListeners(ChangeEvent(ChangeKind.STRUCTURE, activeId))
    }

    fun rename(id: String, newTitle: String) {
        val conversation = get(id) ?: return
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty() || trimmed == conversation.title) return
        conversation.title = trimmed
        notifyListeners(ChangeEvent(ChangeKind.STRUCTURE, id))
    }

    fun updateDraft(id: String, draft: String) {
        val conversation = get(id) ?: return
        conversation.draft = draft
    }

    fun appendToDraft(text: String, block: Boolean = false) {
        val conversation = ensureActiveConversation()
        conversation.draft = mergeDraft(conversation.draft, text, block)
        showToolWindow(focus = true)
        notifyListeners(ChangeEvent(ChangeKind.DRAFT_APPENDED, conversation.id))
    }

    private fun mergeDraft(draft: String, text: String, block: Boolean): String {
        if (draft.isBlank()) return text
        return if (block) {
            draft.trimEnd() + "\n\n" + text
        } else if (draft.endsWith(" ") || draft.endsWith("\n")) {
            draft + text
        } else {
            draft + " " + text
        }
    }

    fun sendDraft(): Boolean {
        val conversation = active() ?: return false
        val text = conversation.draft.trim()
        if (text.isEmpty()) return false

        if (!terminal().isAlive(conversation.id)) {
            terminal().launch(conversation.id, conversation.tabName)
            showNotification(
                "Pi terminal was restarted for ${conversation.title}. Send again after Pi is ready.",
                NotificationType.WARNING
            )
            notifyListeners(ChangeEvent(ChangeKind.STRUCTURE, conversation.id))
            return false
        }

        terminal().sendText(conversation.id, text)
        conversation.messages.add(PiUserMessage(text))
        conversation.draft = ""
        terminal().selectTab(conversation.id, requestFocus = false)
        notifyListeners(ChangeEvent(ChangeKind.SENT, conversation.id))
        return true
    }

    fun isTerminalAlive(id: String): Boolean = terminal().isAlive(id)

    fun focusTerminal(id: String) {
        val conversation = get(id) ?: return
        if (!terminal().isAlive(id)) {
            showNotification("${conversation.title} terminal is closed.", NotificationType.WARNING)
            notifyListeners(ChangeEvent(ChangeKind.STRUCTURE, id))
            return
        }
        terminal().selectTab(id, requestFocus = true)
    }

    fun checkTerminal(id: String): Boolean {
        val conversation = get(id) ?: return false
        val alive = terminal().isAlive(id)
        val status = if (alive) "running" else "closed"
        showNotification("${conversation.title} terminal is $status.", NotificationType.INFORMATION)
        notifyListeners(ChangeEvent(ChangeKind.STRUCTURE, id))
        return alive
    }

    fun showToolWindow(focus: Boolean = true) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        if (focus) {
            toolWindow.activate(null)
        } else {
            toolWindow.show()
        }
    }

    private fun terminal(): PiTerminalService = PiTerminalService.getInstance(project)

    private fun notifyListeners(event: ChangeEvent) {
        listeners.forEach { it.onChanged(event) }
    }

    private fun showNotification(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Pi Agent")
            .createNotification(message, type)
            .notify(project)
    }

    companion object {
        const val TOOL_WINDOW_ID = "Pi Agent"
        private val TITLE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

        fun getInstance(project: Project): PiConversationService = project.service()
    }
}
