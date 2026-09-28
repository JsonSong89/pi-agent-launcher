package com.piagent.launcher.services

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.piagent.launcher.settings.PiSettings
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Manages one Pi terminal tab per conversation.
 */
@Service(Service.Level.PROJECT)
class PiTerminalService(private val project: Project) : Disposable {

    interface SessionListener {
        fun onSessionEnded(conversationId: String) {}
        fun onSessionStarted(conversationId: String) {}
    }

    private class TerminalSession(
        val conversationId: String,
        val tabName: String,
        var component: JComponent? = null,
        var sendTextFn: ((String, Boolean) -> Unit)? = null,
        var content: Content? = null,
        var initialized: Boolean = false
    )

    private val logger = Logger.getInstance(PiTerminalService::class.java)
    private val sessions = ConcurrentHashMap<String, TerminalSession>()
    private val listeners = CopyOnWriteArrayList<SessionListener>()
    private val closingIds = ConcurrentHashMap.newKeySet<String>()
    private val timers = CopyOnWriteArrayList<Timer>()
    var onRestartRequested: ((conversationId: String, tabName: String) -> Unit)? = null
    @Volatile
    private var contentListenerInstalled = false

    fun addListener(listener: SessionListener) {
        listeners.add(listener)
    }

    fun launch(conversationId: String, tabName: String) {
        val existing = sessions[conversationId]
        if (existing != null && isTerminalAlive(existing)) {
            selectTab(conversationId, requestFocus = false)
            return
        }
        if (existing != null) {
            reset(conversationId, notify = false)
        }

        try {
            showNotification("Starting $tabName...", NotificationType.INFORMATION)
            val workingDir = project.basePath ?: System.getProperty("user.home")
            createTerminalTab(conversationId, tabName, workingDir)
            PiStatusWidget.update(project)
            startPiWithDelay(conversationId)
        } catch (e: Exception) {
            logger.error("Failed to launch Pi terminal for $tabName", e)
            reset(conversationId, notify = false)
            showNotification("Failed to start $tabName: ${e.message}", NotificationType.ERROR)
        }
    }

    fun close(conversationId: String) {
        closingIds.add(conversationId)
        try {
            val session = sessions[conversationId]
            if (session != null) {
                closeTerminalTab(session)
            }
            reset(conversationId, notify = false)
            PiStatusWidget.update(project)
        } finally {
            closingIds.remove(conversationId)
        }
    }

    fun isAlive(conversationId: String): Boolean {
        val session = sessions[conversationId] ?: return false
        return isTerminalAlive(session)
    }

    fun runningCount(): Int = sessions.values.count { isTerminalAlive(it) }

    fun isReady(): Boolean = runningCount() > 0

    /**
     * Send one user message to the Pi TUI.
     * executeCommand treats every '\n' as Enter, so a multi-line draft becomes
     * many messages. Paste the whole block (bracketed paste) and submit once.
     */
    fun sendText(conversationId: String, text: String) {
        val fn = sessions[conversationId]?.sendTextFn ?: return
        val body = text.replace("\r\n", "\n").replace('\r', '\n')
        fn("\u001b[200~$body\u001b[201~", false)
        startTimer(Timer(80, null).apply {
            isRepeats = false
            addActionListener { fn("\r", false) }
        })
    }

    /**
     * Insert into the Pi TUI input without submitting.
     */
    fun insertText(conversationId: String, text: String) {
        val fn = sessions[conversationId]?.sendTextFn ?: return
        val body = text.replace("\r\n", "\n").replace('\r', '\n')
        fn("\u001b[200~$body\u001b[201~", false)
    }

    fun selectTab(conversationId: String, requestFocus: Boolean) {
        val session = sessions[conversationId] ?: return
        if (!isTerminalAlive(session)) return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal") ?: return
        toolWindow.show {
            selectSessionContent(session, requestFocus)
        }
    }

    private fun isTerminalAlive(session: TerminalSession): Boolean {
        val component = session.component ?: return false
        if (component.parent == null) return false
        val content = session.content
        if (content != null) {
            val manager = content.manager ?: return false
            if (!manager.contents.contains(content)) return false
        }
        return session.initialized
    }

    @Suppress("DEPRECATION")
    private fun createTerminalTab(conversationId: String, tabName: String, workingDir: String) {
        val managerClass = Class.forName("org.jetbrains.plugins.terminal.TerminalToolWindowManager")
        val getInstanceMethod = managerClass.getMethod("getInstance", Project::class.java)
        val manager = getInstanceMethod.invoke(null, project)

        val createMethod = managerClass.getMethod(
            "createLocalShellWidget",
            String::class.java,
            String::class.java
        )
        val widget = createMethod.invoke(manager, workingDir, tabName)
        val component = widget.javaClass.getMethod("getComponent").invoke(widget) as JComponent

        val session = TerminalSession(
            conversationId = conversationId,
            tabName = tabName,
            component = component,
            sendTextFn = { text, execute ->
                try {
                    if (execute) {
                        widget.javaClass.getMethod("executeCommand", String::class.java)
                            .invoke(widget, text)
                    } else {
                        val starter = widget.javaClass.getMethod("getTerminalStarter").invoke(widget)
                        if (starter != null) {
                            starter.javaClass.getMethod(
                                "sendString",
                                String::class.java,
                                Boolean::class.javaPrimitiveType
                            ).invoke(starter, text, false)
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to send text to $tabName: ${e.message}")
                }
            },
            initialized = true
        )
        session.content = findContent(session)
        sessions[conversationId] = session
        ensureContentListener()
        selectTab(conversationId, requestFocus = false)
        monitorProcess(session, widget)
        listeners.forEach { it.onSessionStarted(conversationId) }
        logger.info("Pi terminal tab created: $tabName")
    }

    private fun findContent(session: TerminalSession): Content? {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal") ?: return null
        val component = session.component ?: return null
        return toolWindow.contentManager.contents.firstOrNull { content ->
            content.component == component ||
                SwingUtilities.isDescendingFrom(component, content.component)
        } ?: toolWindow.contentManager.contents.firstOrNull { content ->
            content.displayName == session.tabName
        }
    }

    private fun selectSessionContent(session: TerminalSession, requestFocus: Boolean) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal") ?: return
        val contentManager = toolWindow.contentManager
        val content = session.content?.takeIf { contentManager.contents.contains(it) }
            ?: findContent(session)?.also { session.content = it }
        if (content != null) {
            contentManager.setSelectedContent(content, requestFocus)
        }
        if (requestFocus) {
            session.component?.requestFocusInWindow()
        }
    }

    private fun closeTerminalTab(session: TerminalSession) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal") ?: return
        val contentManager = toolWindow.contentManager
        val content = session.content?.takeIf { contentManager.contents.contains(it) }
            ?: findContent(session)
        if (content != null) {
            contentManager.removeContent(content, true)
        }
    }

    private fun ensureContentListener() {
        if (contentListenerInstalled) return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal") ?: return
        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun contentRemoved(event: ContentManagerEvent) {
                val removed = event.content
                val session = sessions.values.firstOrNull { session ->
                    session.content == removed ||
                        session.component?.let { SwingUtilities.isDescendingFrom(it, removed.component) } == true
                } ?: return
                val notify = session.conversationId !in closingIds
                reset(session.conversationId, notify = notify)
                PiStatusWidget.update(project)
            }
        })
        contentListenerInstalled = true
    }

    private fun startPiWithDelay(conversationId: String) {
        val settings = PiSettings.getInstance().state
        val command = buildString {
            append(settings.piCommand)

            val model = if (settings.customModelId.isNotBlank()) {
                settings.customModelId
            } else if (settings.model != "Default") {
                settings.model
            } else null

            if (model != null) {
                append(" --model $model")
            }

            if (settings.thinkingLevel != "Default" && settings.thinkingLevel.isNotBlank()) {
                append(" --thinking ${settings.thinkingLevel}")
            }

            if (settings.extraArgs.isNotBlank()) {
                append(" ")
                append(settings.extraArgs)
            }
        }

        var attempts = 0
        val timer = object : Timer(300, null) {
            init {
                isRepeats = true
                addActionListener {
                    attempts++
                    val fn = sessions[conversationId]?.sendTextFn
                    if (fn != null) {
                        fn(command, true)
                        stop()
                    } else if (attempts > 20) {
                        logger.warn("Terminal not ready after 6s for $conversationId")
                        stop()
                    }
                }
            }
        }
        startTimer(timer)
    }

    private fun monitorProcess(session: TerminalSession, widget: Any) {
        val conversationId = session.conversationId
        val checkTimer = object : Timer(2000, null) {
            init {
                isRepeats = true
                addActionListener {
                    try {
                        val current = sessions[conversationId]
                        if (current == null || !current.initialized) {
                            stop()
                            return@addActionListener
                        }
                        val connector = widget.javaClass.getMethod("getTtyConnector").invoke(widget)
                        if (connector != null) {
                            val isConnected = connector.javaClass.getMethod("isConnected").invoke(connector) as Boolean
                            if (!isConnected) {
                                stop()
                                handleProcessExit(conversationId, session.tabName)
                            }
                        }
                    } catch (_: Exception) {
                        stop()
                    }
                }
            }
        }
        startTimer(Timer(5000, null).apply {
            isRepeats = false
            addActionListener { startTimer(checkTimer) }
        })
    }

    private fun handleProcessExit(conversationId: String, tabName: String) {
        val session = sessions[conversationId]
        if (session != null) {
            closingIds.add(conversationId)
            try {
                closeTerminalTab(session)
            } finally {
                closingIds.remove(conversationId)
            }
        }
        reset(conversationId, notify = true)
        PiStatusWidget.update(project)
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("Pi Agent")
            .createNotification(
                "$tabName process exited unexpectedly",
                "Click to restart Pi",
                NotificationType.WARNING
            )
        notification.addAction(object : NotificationAction("Restart Pi") {
            override fun actionPerformed(e: AnActionEvent, notification: com.intellij.notification.Notification) {
                notification.expire()
                val handler = onRestartRequested
                if (handler != null) {
                    handler(conversationId, tabName)
                } else {
                    launch(conversationId, tabName)
                }
            }
        })
        notification.notify(project)
    }

    private fun startTimer(timer: Timer) {
        timers.add(timer)
        timer.start()
    }

    override fun dispose() {
        timers.forEach { it.stop() }
        timers.clear()
        sessions.clear()
        listeners.clear()
    }

    private fun reset(conversationId: String, notify: Boolean) {
        sessions.remove(conversationId)
        if (notify) {
            listeners.forEach { it.onSessionEnded(conversationId) }
        }
    }

    private fun showNotification(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Pi Agent")
            .createNotification(message, type)
            .notify(project)
    }

    companion object {
        fun getInstance(project: Project): PiTerminalService = project.service()
    }
}
