package com.onthecrow.onthecrowvpn.navigation

internal sealed interface NavigationCommand {
    data class To(val destination: Destination) : NavigationCommand
    data object Back : NavigationCommand
}
