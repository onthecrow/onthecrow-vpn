package com.onthecrow.onthecrowvpn.navigation.di

import com.onthecrow.onthecrowvpn.navigation.Destination
import com.onthecrow.onthecrowvpn.navigation.NavigationProvider
import com.onthecrow.onthecrowvpn.navigation.NavigationProviderImpl
import com.onthecrow.onthecrowvpn.navigation.Navigator
import com.onthecrow.onthecrowvpn.navigation.NavigatorImpl
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
