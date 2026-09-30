package com.piagent.launcher.conversations

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Persistent index of conversations (project level). Pi's own session jsonl
 * files remain the authoritative store of conversation data; this only keeps
 * the plugin-side index (id, title, piSessionId, draft, user message log).
 */
@Service(Service.Level.PROJECT)
@State(
    name = "PiAgentLauncherConversations",
    storages = [Storage("PiAgentLauncherConversations.xml")]
)
class PiConversationStore(private val project: Project) : PersistentStateComponent<PiConversationStore.State> {

    class Entry {
        var id: String = ""
        var title: String = ""
        var tabName: String = ""
        var piSessionId: String = ""
        var createdAt: Long = 0
        var draft: String = ""
        var messages: MutableList<Msg> = mutableListOf()
    }

    class Msg {
        var text: String = ""
        var timestamp: Long = 0
    }

    class State {
        var entries: MutableList<Entry> = mutableListOf()
        var activeId: String? = null
        var titleSeq: Int = 1
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        // Drop entries that lost required fields (corrupt/legacy XML).
        state.entries = state.entries.filter {
            it.id.isNotBlank() && it.piSessionId.isNotBlank() && it.title.isNotBlank()
        }.toMutableList()
        if (state.activeId != null && state.entries.none { it.id == state.activeId }) {
            state.activeId = null
        }
        myState = state
    }

    fun snapshot(): List<StoredConversation> = myState.entries.map { e ->
        StoredConversation(
            id = e.id,
            title = e.title,
            tabName = e.tabName,
            piSessionId = e.piSessionId,
            createdAt = e.createdAt,
            draft = e.draft,
            messages = e.messages.map { PiUserMessage(it.text, it.timestamp) }
        )
    }

    fun activeId(): String? = myState.activeId

    fun titleSeq(): Int = myState.titleSeq

    fun nextTitleSeq(): Int = myState.titleSeq++

    fun save(conversations: List<PiConversation>, activeId: String?) {
        myState.entries = conversations.map { c ->
            Entry().apply {
                id = c.id
                title = c.title
                tabName = c.tabName
                piSessionId = c.piSessionId
                createdAt = c.createdAt
                draft = c.draft
                messages = c.messages.map { m ->
                    Msg().apply {
                        text = m.text
                        timestamp = m.timestamp
                    }
                }.toMutableList()
            }
        }.toMutableList()
        myState.activeId = activeId
    }

    data class StoredConversation(
        val id: String,
        val title: String,
        val tabName: String,
        val piSessionId: String,
        val createdAt: Long,
        val draft: String,
        val messages: List<PiUserMessage>
    )

    companion object {
        fun getInstance(project: Project): PiConversationStore = project.service()
    }
}
