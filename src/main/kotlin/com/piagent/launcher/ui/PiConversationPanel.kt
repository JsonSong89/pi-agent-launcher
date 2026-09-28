package com.piagent.launcher.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.piagent.launcher.conversations.PiConversation
import com.piagent.launcher.conversations.PiConversationService
import com.piagent.launcher.conversations.PiConversationService.ChangeKind
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Right-side conversation manager: history dropdown, user send log, draft input.
 */
class PiConversationPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val conversations = PiConversationService.getInstance(project)
    private val combo = ComboBox<PiConversation>()
    private val statusLabel = JBLabel()
    private val historyArea = JBTextArea()
    private val inputArea = JBTextArea()
    private val sendButton = JButton("Send")
    private val timeFormat = SimpleDateFormat("HH:mm")
    private var syncing = false
    @Volatile
    private var disposed = false

    private val listener = PiConversationService.Listener { event ->
        SwingUtilities.invokeLater {
            if (disposed) return@invokeLater
            refreshUi()
            if (event.kind == ChangeKind.DRAFT_APPENDED) {
                inputArea.requestFocusInWindow()
                inputArea.caretPosition = inputArea.document.length
            }
        }
    }

    init {
        conversations.addListener(listener)
        setContent(buildUi())
        refreshUi()
    }

    private fun buildUi(): JPanel {
        combo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {
                val conversation = value as? PiConversation
                val label = super.getListCellRendererComponent(list, conversation?.title ?: "No conversations", index, isSelected, cellHasFocus)
                if (conversation != null && !conversations.isTerminalAlive(conversation.id)) {
                    foreground = if (isSelected) foreground else JBColor.GRAY
                    text = "${conversation.title} (closed)"
                }
                return label
            }
        }
        combo.addActionListener {
            if (syncing) return@addActionListener
            val selected = combo.selectedItem as? PiConversation ?: return@addActionListener
            persistDraft()
            conversations.setActive(selected.id)
        }

        val toolbar = ActionManager.getInstance().createActionToolbar(
            "PiConversationsToolbar",
            DefaultActionGroup().apply {
                add(NewConversationAction())
                add(DeleteConversationAction())
                addSeparator()
                add(CheckTerminalAction())
                add(FocusTerminalAction())
            },
            true
        )
        toolbar.targetComponent = this

        val top = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.empty(6, 8, 4, 8)
            add(combo, BorderLayout.CENTER)
            add(toolbar.component, BorderLayout.EAST)
        }

        statusLabel.border = JBUI.Borders.empty(0, 8, 6, 8)
        statusLabel.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        statusLabel.font = JBUI.Fonts.smallFont()

        val header = JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(statusLabel, BorderLayout.SOUTH)
        }

        historyArea.apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
            emptyText.text = "User messages will appear here"
        }

        inputArea.apply {
            lineWrap = true
            wrapStyleWord = true
            emptyText.text = "Message the active conversation…"
            document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = persistDraft()
                override fun removeUpdate(e: DocumentEvent) = persistDraft()
                override fun changedUpdate(e: DocumentEvent) = persistDraft()
            })
            inputMap.put(KeyStroke.getKeyStroke("ENTER"), "send-pi")
            actionMap.put("send-pi", object : javax.swing.AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent) {
                    sendDraft()
                }
            })
            inputMap.put(KeyStroke.getKeyStroke("shift ENTER"), "insert-break")
        }

        sendButton.addActionListener { sendDraft() }

        val inputPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8, 8, 8)
            preferredSize = Dimension(0, JBUI.scale(120))
            add(JBScrollPane(inputArea), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 4)).apply {
                add(sendButton)
            }, BorderLayout.SOUTH)
        }

        return JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(
                historyArea,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            ), BorderLayout.CENTER)
            add(inputPanel, BorderLayout.SOUTH)
        }
    }

    private fun refreshUi() {
        syncing = true
        try {
            val items = conversations.all()
            val active = conversations.active()
            val model = DefaultComboBoxModel<PiConversation>()
            items.forEach { model.addElement(it) }
            combo.model = model
            combo.selectedItem = active
            combo.isEnabled = items.isNotEmpty()

            historyArea.text = formatHistory(active)
            historyArea.caretPosition = historyArea.document.length

            if (inputArea.text != (active?.draft ?: "")) {
                inputArea.text = active?.draft ?: ""
            }

            val alive = active != null && conversations.isTerminalAlive(active.id)
            statusLabel.text = when {
                active == null -> "No active conversation"
                alive -> "${active.title} · terminal running"
                else -> "${active.title} · terminal closed"
            }
            sendButton.isEnabled = active != null
            inputArea.isEnabled = active != null
        } finally {
            syncing = false
        }
    }

    private fun formatHistory(conversation: PiConversation?): String {
        if (conversation == null || conversation.messages.isEmpty()) return ""
        return conversation.messages.joinToString("\n\n") { message ->
            val time = timeFormat.format(Date(message.timestamp))
            "[$time]\n${message.text}"
        }
    }

    private fun persistDraft() {
        if (syncing) return
        val id = conversations.active()?.id ?: return
        conversations.updateDraft(id, inputArea.text)
    }

    private fun sendDraft() {
        persistDraft()
        conversations.sendDraft()
    }

    override fun dispose() {
        disposed = true
        persistDraft()
        conversations.removeListener(listener)
    }

    private inner class NewConversationAction : AnAction("New Conversation", "Start a new Pi conversation", AllIcons.General.Add), DumbAware {
        override fun actionPerformed(e: AnActionEvent) {
            persistDraft()
            conversations.createConversation()
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class DeleteConversationAction : AnAction("Delete Conversation", "Close the terminal and delete this conversation", AllIcons.General.Remove), DumbAware {
        override fun actionPerformed(e: AnActionEvent) {
            val active = conversations.active() ?: return
            val confirmed = Messages.showYesNoDialog(
                project,
                "Delete ${active.title}? This closes its terminal and removes local send history.",
                "Delete Conversation",
                Messages.getQuestionIcon()
            ) == Messages.YES
            if (!confirmed) return
            conversations.deleteConversation(active.id)
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = conversations.active() != null
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class CheckTerminalAction : AnAction("Check Terminal", "Check whether the Pi terminal is still running", AllIcons.Actions.Refresh), DumbAware {
        override fun actionPerformed(e: AnActionEvent) {
            val id = conversations.active()?.id ?: return
            conversations.checkTerminal(id)
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = conversations.active() != null
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class FocusTerminalAction : AnAction("Focus Terminal", "Focus the terminal bound to this conversation", AllIcons.General.Locate), DumbAware {
        override fun actionPerformed(e: AnActionEvent) {
            val id = conversations.active()?.id ?: return
            conversations.focusTerminal(id)
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = conversations.active() != null
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }
}
