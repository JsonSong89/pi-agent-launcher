package com.piagent.launcher.conversations

/**
 * In-memory conversation bound to one Pi terminal tab.
 * Not persisted across IDE restarts.
 */
class PiConversation(
    val id: String,
    val title: String,
    val tabName: String,
    val createdAt: Long = System.currentTimeMillis(),
    val messages: MutableList<PiUserMessage> = mutableListOf(),
    var draft: String = ""
) {
    override fun equals(other: Any?): Boolean = other is PiConversation && other.id == id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = title
}

data class PiUserMessage(
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)
