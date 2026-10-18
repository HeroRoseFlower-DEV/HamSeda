package com.hamseda.walkie.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hamseda.walkie.messaging.MessagingEngine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Conversations list: recency-ordered threads with unread badges. */
class MessagesViewModel(deps: VmDeps) : ViewModel() {
    private val engine: MessagingEngine = deps.messaging
    val conversations: StateFlow<List<MessagingEngine.ConversationView>> = engine.conversations
    val unreadTotal: StateFlow<Int> = engine.unreadTotal
    val online: StateFlow<Boolean> = engine.online
    val livePeerId: StateFlow<String> = engine.livePeerId
    val livePeerName: StateFlow<String> = engine.livePeerName
}

/**
 * One chat thread. Marks messages read while visible (drives read receipts)
 * and routes typing state to the engine. Setting the active peer in init /
 * clearing in onCleared keeps typing indicators and read routing correct.
 */
class ChatViewModel(
    private val deps: VmDeps,
    val peerId: String,
) : ViewModel() {
    private val engine: MessagingEngine = deps.messaging

    val messages: StateFlow<List<MessagingEngine.MessageView>> =
        engine.observeMessages(peerId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val peerTyping: StateFlow<Boolean> = engine.peerTyping
    val online: StateFlow<Boolean> = engine.online

    /** Peer display name from the latest conversation snapshot. */
    val peerName: StateFlow<String> =
        engine.conversations
            .map { list ->
                list.firstOrNull { it.peerId == peerId }?.peerName
                    ?: MessagingEngine.shortId(peerId)
            }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                MessagingEngine.shortId(peerId),
            )

    init {
        engine.setActivePeer(peerId)
        viewModelScope.launch { engine.markReadVisible(peerId) }
    }

    fun send(text: String) {
        viewModelScope.launch {
            runCatching { engine.sendText(peerId, text) }
                .onFailure { android.util.Log.w("ChatViewModel", "send failed", it) }
        }
    }

    fun onTyping(typing: Boolean) = engine.setTyping(typing)

    /** Re-marks read when new messages arrive while the thread is open. */
    fun onMessagesVisible() {
        viewModelScope.launch { engine.markReadVisible(peerId) }
    }

    override fun onCleared() {
        engine.setActivePeer(null)
        super.onCleared()
    }
}

/** Factory for the chat thread ViewModel, which needs a peerId. */
class ChatVmFactory(
    private val deps: VmDeps,
    private val peerId: String,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            ChatViewModel(deps, peerId) as T
        } else {
            throw IllegalArgumentException("unknown VM ${modelClass.name}")
        }
}
