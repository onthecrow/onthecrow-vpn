package com.onthecrow.deltavpn.uicore

interface Reducer<S : State, E : Event> {
    suspend fun reduce(state: S, event: E): S
}
