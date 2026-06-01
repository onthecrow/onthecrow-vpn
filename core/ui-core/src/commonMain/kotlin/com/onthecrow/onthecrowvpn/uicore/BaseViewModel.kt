package com.onthecrow.onthecrowvpn.uicore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn

abstract class BaseViewModel<E : Event, S : State, R : Reducer<S, E>>(
    private val reducer: R,
) : ViewModel() {
    private val events = MutableSharedFlow<E>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val eventFlow = events.asSharedFlow()

    val state: StateFlow<S> = events
        .runningFold(getInitialState(), reducer::reduce)
        .stateIn(viewModelScope, SharingStarted.Eagerly, getInitialState())

    abstract fun getInitialState(): S

    fun onEvent(event: E) {
        events.tryEmit(event)
    }
}
