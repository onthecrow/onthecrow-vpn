package com.onthecrow.onthecrowvpn.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import org.koin.core.module.Module
import org.koin.dsl.bind
import kotlin.reflect.KClass

interface FeatureEntry<T : Destination> {
    val keyClass: KClass<T>
    val serializer: KSerializer<T>
    val content: @Composable (T, Modifier) -> Unit
}

inline fun <reified T : Destination> Module.registerScreen(
    noinline content: @Composable (T, Modifier) -> Unit,
) {
    val entry = object : FeatureEntry<T> {
        override val keyClass: KClass<T> = T::class
        override val serializer: KSerializer<T> = serializer<T>()
        override val content: @Composable (T, Modifier) -> Unit = content
    }
    single { entry } bind FeatureEntry::class
}
