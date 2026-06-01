package com.onthecrow.onthecrowvpn.navigation

import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import org.koin.core.component.KoinComponent
import kotlin.reflect.KClass

internal class NavigationProviderImpl(
    private val navigator: NavigatorImpl,
    private val startDestination: Destination,
) : NavigationProvider, KoinComponent {
    @Composable
    override fun Navigation(modifier: Modifier) {
        val koin = getKoin()
        val entries = remember(koin) { koin.getAll<FeatureEntry<*>>() }
        val entryMap = remember(entries) { entries.associateBy { it.keyClass } }
        val config = remember(entries) {
            SavedStateConfiguration {
                serializersModule = SerializersModule {
                    polymorphic(NavKey::class) {
                        entries.forEach { entry ->
                            @Suppress("UNCHECKED_CAST")
                            val keyClass = entry.keyClass as KClass<NavKey>
                            @Suppress("UNCHECKED_CAST")
                            val serializer = entry.serializer as KSerializer<NavKey>
                            subclass(keyClass, serializer)
                        }
                    }
                }
            }
        }
        val backStack = rememberNavBackStack(config, startDestination)

        LaunchedEffect(navigator) {
            navigator.commands.collect { command ->
                when (command) {
                    is NavigationCommand.To -> backStack.add(command.destination)
                    NavigationCommand.Back -> if (backStack.size > 1) backStack.removeLastOrNull()
                }
            }
        }

        NavDisplay(
            modifier = modifier,
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            transitionSpec = {
                slideInHorizontally(initialOffsetX = { it }) togetherWith
                    slideOutHorizontally(targetOffsetX = { -it })
            },
            popTransitionSpec = {
                slideInHorizontally(initialOffsetX = { -it }) togetherWith
                    slideOutHorizontally(targetOffsetX = { it })
            },
            predictivePopTransitionSpec = {
                slideInHorizontally(initialOffsetX = { -it }) togetherWith
                    slideOutHorizontally(targetOffsetX = { it })
            },
        ) { key ->
            NavEntry(key = key) {
                val entry = entryMap[key::class]
                @Suppress("UNCHECKED_CAST")
                val typedEntry = entry as? FeatureEntry<Destination>
                val destination = key as? Destination
                if (typedEntry != null && destination != null) {
                    typedEntry.content(destination, Modifier.fillMaxSize())
                }
            }
        }
    }
}
