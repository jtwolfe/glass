package com.jtwolfe.glass.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jtwolfe.glass.data.ChatRepository
import com.jtwolfe.glass.data.Message
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ChatViewModel : ViewModel() {

    private val repository = ChatRepository()

    val messages: StateFlow<List<Message>> = repository.messages
    val isLocalOnly: StateFlow<Boolean> = repository.isLocalOnly

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private var pollingJob: Job? = null

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        viewModelScope.launch {
            _isSending.value = true
            repository.sendMessage(text)
            _isSending.value = false
        }
    }

    fun startPolling() {
        if (pollingJob?.isActive == true) return
        if (isLocalOnly.value) return

        pollingJob = viewModelScope.launch {
            while (isActive) {
                repository.pollReplies()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 3000L
    }
}
