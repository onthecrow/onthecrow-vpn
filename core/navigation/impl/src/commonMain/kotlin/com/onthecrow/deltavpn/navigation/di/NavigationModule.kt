package com.onthecrow.deltavpn.navigation.di

import com.onthecrow.deltavpn.navigation.Destination
import com.onthecrow.deltavpn.navigation.NavigationProvider
import com.onthecrow.deltavpn.navigation.NavigationProviderImpl
import com.onthecrow.deltavpn.navigation.Navigator
import com.onthecrow.deltavpn.navigation.NavigatorImpl
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module

val StartDestination = named("StartDestination")

val navigationModule = module {
    single { NavigatorImpl() } binds arrayOf(Navigator::class, NavigatorImpl::class)

    single {
        NavigationProviderImpl(
            navigator = get(),
            startDestination = get(StartDestination),
        )
    } bind NavigationProvider::class
}
