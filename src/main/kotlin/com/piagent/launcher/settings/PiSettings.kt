package com.piagent.launcher.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.util.ui.JBUI
import java.awt.Font
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Persistent settings for Pi Agent plugin.
 * Storage name was reset so older XML (boolean defaults that could not persist
 * unchecked options) is ignored.
 */
@Service(Service.Level.APP)
@State(
    name = "PiAgentLauncherSettings",
    storages = [Storage("PiAgentLauncherSettings.xml")]
)
class PiSettings : PersistentStateComponent<PiSettings.State> {

    class State {
        var piCommand: String = "pi"
        var model: String = "Default"
        var customModelId: String = ""
        var thinkingLevel: String = "Default"
        var extraArgs: String = ""
        var shellPath: String = ""
        var conversationFont: String = FONT_IDE_EDITOR
        var conversationFontSize: Int = DEFAULT_FONT_SIZE
        // Align with Java/XML boolean default (false) so unchecked values persist.
        var autoOpenFiles: Boolean = false
        var showNotifications: Boolean = false
        var sendWithCtrlEnter: Boolean = false
    }

    private var myState = State()
    private val listeners = CopyOnWriteArrayList<ChangeListener>()

    fun interface ChangeListener {
        fun settingsChanged()
    }

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    override fun noStateLoaded() {
        myState.autoOpenFiles = true
        myState.showNotifications = true
        myState.sendWithCtrlEnter = true
        myState.conversationFont = FONT_IDE_EDITOR
        myState.conversationFontSize = DEFAULT_FONT_SIZE
    }

    fun addChangeListener(listener: ChangeListener) {
        listeners.add(listener)
    }

    fun removeChangeListener(listener: ChangeListener) {
        listeners.remove(listener)
    }

    fun notifyChanged() {
        listeners.forEach { it.settingsChanged() }
    }

    fun conversationFont(): Font {
        val size = myState.conversationFontSize.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        return Font(resolveFontFamily(myState.conversationFont), Font.PLAIN, size)
    }

    companion object {
        const val FONT_IDE_EDITOR = "IDE Editor"
        const val FONT_IDE_CONSOLE = "IDE Console"
        const val FONT_IDE_UI = "IDE UI"
        const val DEFAULT_FONT_SIZE = 14
        const val MIN_FONT_SIZE = 8
        const val MAX_FONT_SIZE = 32

        fun getInstance(): PiSettings = service()

        fun resolveFontFamily(name: String): String {
            val scheme = EditorColorsManager.getInstance().globalScheme
            return when (name) {
                FONT_IDE_EDITOR -> scheme.editorFontName
                FONT_IDE_CONSOLE -> scheme.consoleFontName.ifBlank { scheme.editorFontName }
                FONT_IDE_UI -> JBUI.Fonts.label().family
                else -> name.ifBlank { Font.MONOSPACED }
            }
        }
    }
}
