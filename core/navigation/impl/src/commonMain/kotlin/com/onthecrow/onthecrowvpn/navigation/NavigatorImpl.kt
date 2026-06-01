package com.onthecrow.onthecrowvpn.navigation

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

internal class NavigatorImpl : Navigator {
    private val navigationCommands = Channel<NavigationCommand>(Channel.BUFFERED)
    val commands = navigationCommands.receiveAsFlow()

    override fun navigate(destination: Destination) {
        navigationCommands.trySend(NavigationCommand.To(destination))
    }

    override fun back() {
        navigationCommands.trySend(NavigationCommand.Back)
    }
}
