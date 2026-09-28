package com.piagent.launcher.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.GraphicsEnvironment
import javax.swing.*

/**
 * Settings UI: Settings → Tools → Pi Agent
 */
class PiSettingsConfigurable : Configurable {

    private var panel: JPanel? = null
    private var piCommandField: JBTextField? = null
    private var modelCombo: ComboBox<String>? = null
    private var customModelField: JBTextField? = null
    private var thinkingLevelCombo: ComboBox<String>? = null
    private var extraArgsField: JBTextField? = null
    private var autoOpenFilesCheckbox: JCheckBox? = null
    private var showNotificationsCheckbox: JCheckBox? = null
    private var sendShortcutCombo: ComboBox<String>? = null
    private var conversationFontCombo: ComboBox<String>? = null
    private var conversationFontSizeSpinner: JSpinner? = null

    companion object {
        const val SEND_CTRL_ENTER = "Ctrl+Enter"
        const val SEND_ENTER = "Enter"
        val SEND_SHORTCUTS = arrayOf(SEND_CTRL_ENTER, SEND_ENTER)

        val THINKING_LEVELS = arrayOf(
            "Default",
            "none",
            "low",
            "medium",
            "high",
            "max"
        )

        fun loadModelOptions(): Array<String> {
            val models = PiModelLoader.loadModels()
            val options = mutableListOf("Default")
            models.forEach { options.add("${it.provider}/${it.id}") }
            return options.toTypedArray()
        }

        fun loadFontOptions(): Array<String> {
            val presets = listOf(
                PiSettings.FONT_IDE_EDITOR,
                PiSettings.FONT_IDE_CONSOLE,
                PiSettings.FONT_IDE_UI
            )
            val system = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .availableFontFamilyNames
                .sorted()
            return (presets + system).distinct().toTypedArray()
        }
    }

    override fun getDisplayName(): String = "Pi Agent"

    override fun createComponent(): JComponent {
        val settings = PiSettings.getInstance().state

        piCommandField = JBTextField(settings.piCommand)
        val modelOptions = loadModelOptions()
        modelCombo = ComboBox(modelOptions).apply {
            selectedItem = if (settings.model in modelOptions) settings.model else "Default"
            preferredSize = java.awt.Dimension(400, preferredSize.height)
        }
        customModelField = JBTextField(settings.customModelId).apply {
            emptyText.text = "e.g. claude-sonnet-4-20250514"
            preferredSize = java.awt.Dimension(400, preferredSize.height)
        }
        thinkingLevelCombo = ComboBox(THINKING_LEVELS).apply {
            selectedItem = if (settings.thinkingLevel in THINKING_LEVELS) settings.thinkingLevel else "Default"
            preferredSize = java.awt.Dimension(400, preferredSize.height)
        }
        extraArgsField = JBTextField(settings.extraArgs).apply {
            emptyText.text = "e.g. --no-themes --verbose"
        }
        autoOpenFilesCheckbox = JCheckBox("Auto-open files modified by Pi", settings.autoOpenFiles)
        showNotificationsCheckbox = JCheckBox("Show notification when Pi finishes", settings.showNotifications)
        sendShortcutCombo = ComboBox(SEND_SHORTCUTS).apply {
            selectedItem = if (settings.sendWithCtrlEnter) SEND_CTRL_ENTER else SEND_ENTER
            preferredSize = java.awt.Dimension(400, preferredSize.height)
        }
        val fontOptions = loadFontOptions()
        conversationFontCombo = ComboBox(fontOptions).apply {
            val current = settings.conversationFont
            if (current.isNotBlank() && current !in fontOptions) {
                addItem(current)
            }
            selectedItem = current.ifBlank { PiSettings.FONT_IDE_EDITOR }
            preferredSize = java.awt.Dimension(400, preferredSize.height)
        }
        conversationFontSizeSpinner = JSpinner(
            SpinnerNumberModel(
                settings.conversationFontSize.coerceIn(PiSettings.MIN_FONT_SIZE, PiSettings.MAX_FONT_SIZE),
                PiSettings.MIN_FONT_SIZE,
                PiSettings.MAX_FONT_SIZE,
                1
            )
        )

        panel = FormBuilder.createFormBuilder()
            // Model section
            .addSeparator()
            .addComponent(JBLabel("Model").apply {
                font = font.deriveFont(java.awt.Font.BOLD)
                border = JBUI.Borders.emptyTop(4)
            })
            .addLabeledComponent(JBLabel("Model:"), modelCombo!!, 1, false)
            .addLabeledComponent(JBLabel("Custom model id:"), customModelField!!, 1, false)
            .addComponentToRightColumn(JBLabel("If set, overrides the model dropdown.").apply {
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                font = JBUI.Fonts.smallFont()
            }, 0)
            .addLabeledComponent(JBLabel("Thinking level:"), thinkingLevelCombo!!, 1, false)
            .addComponentToRightColumn(JBLabel("Some models may not support all thinking levels.").apply {
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                font = JBUI.Fonts.smallFont()
            }, 0)

            // General section
            .addSeparator()
            .addComponent(JBLabel("General").apply {
                font = font.deriveFont(java.awt.Font.BOLD)
                border = JBUI.Borders.emptyTop(4)
            })
            .addLabeledComponent(JBLabel("Pi command:"), piCommandField!!, 1, false)
            .addLabeledComponent(JBLabel("Extra arguments:"), extraArgsField!!, 1, false)

            // Options section
            .addSeparator()
            .addComponent(JBLabel("Options").apply {
                font = font.deriveFont(java.awt.Font.BOLD)
                border = JBUI.Borders.emptyTop(4)
            })
            .addComponent(autoOpenFilesCheckbox!!, 1)
            .addComponent(showNotificationsCheckbox!!, 1)
            .addLabeledComponent(JBLabel("Send shortcut:"), sendShortcutCombo!!, 1, false)
            .addComponentToRightColumn(JBLabel("Ctrl+Enter avoids IME Enter confirming a candidate and sending by mistake.").apply {
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                font = JBUI.Fonts.smallFont()
            }, 0)
            .addLabeledComponent(JBLabel("Conversation font:"), conversationFontCombo!!, 1, false)
            .addComponentToRightColumn(JBLabel("Applies to history and input. IDE Editor / Console / UI follow the current IDE scheme.").apply {
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                font = JBUI.Fonts.smallFont()
            }, 0)
            .addLabeledComponent(JBLabel("Conversation font size:"), conversationFontSizeSpinner!!, 1, false)

            .addComponentFillVertically(JPanel(), 0)
            .panel

        return panel!!
    }

    override fun isModified(): Boolean {
        val settings = PiSettings.getInstance().state
        return piCommandField?.text != settings.piCommand ||
                modelCombo?.selectedItem != settings.model ||
                customModelField?.text != settings.customModelId ||
                thinkingLevelCombo?.selectedItem != settings.thinkingLevel ||
                extraArgsField?.text != settings.extraArgs ||
                autoOpenFilesCheckbox?.isSelected != settings.autoOpenFiles ||
                showNotificationsCheckbox?.isSelected != settings.showNotifications ||
                (sendShortcutCombo?.selectedItem == SEND_CTRL_ENTER) != settings.sendWithCtrlEnter ||
                conversationFontCombo?.selectedItem != settings.conversationFont ||
                fontSizeValue() != settings.conversationFontSize
    }

    override fun apply() {
        val state = PiSettings.getInstance().state
        state.piCommand = piCommandField?.text ?: "pi"
        state.model = modelCombo?.selectedItem as? String ?: "Default"
        state.customModelId = customModelField?.text ?: ""
        state.thinkingLevel = thinkingLevelCombo?.selectedItem as? String ?: "Default"
        state.extraArgs = extraArgsField?.text ?: ""
        state.autoOpenFiles = autoOpenFilesCheckbox?.isSelected == true
        state.showNotifications = showNotificationsCheckbox?.isSelected == true
        state.sendWithCtrlEnter = sendShortcutCombo?.selectedItem == SEND_CTRL_ENTER
        state.conversationFont = conversationFontCombo?.selectedItem as? String ?: PiSettings.FONT_IDE_EDITOR
        state.conversationFontSize = fontSizeValue()
        PiSettings.getInstance().notifyChanged()
    }

    override fun reset() {
        val settings = PiSettings.getInstance().state
        piCommandField?.text = settings.piCommand
        modelCombo?.selectedItem = settings.model
        customModelField?.text = settings.customModelId
        thinkingLevelCombo?.selectedItem = settings.thinkingLevel
        extraArgsField?.text = settings.extraArgs
        autoOpenFilesCheckbox?.isSelected = settings.autoOpenFiles
        showNotificationsCheckbox?.isSelected = settings.showNotifications
        sendShortcutCombo?.selectedItem = if (settings.sendWithCtrlEnter) SEND_CTRL_ENTER else SEND_ENTER
        conversationFontCombo?.selectedItem = settings.conversationFont.ifBlank { PiSettings.FONT_IDE_EDITOR }
        conversationFontSizeSpinner?.value = settings.conversationFontSize.coerceIn(
            PiSettings.MIN_FONT_SIZE,
            PiSettings.MAX_FONT_SIZE
        )
    }

    private fun fontSizeValue(): Int {
        return (conversationFontSizeSpinner?.value as? Number)?.toInt() ?: PiSettings.DEFAULT_FONT_SIZE
    }

    override fun disposeUIResources() {
        panel = null
    }
}
