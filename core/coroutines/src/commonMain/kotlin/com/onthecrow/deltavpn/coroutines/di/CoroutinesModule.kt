package com.onthecrow.deltavpn.coroutines.di

import com.onthecrow.deltavpn.coroutines.ApplicationScopeProvider
import com.onthecrow.deltavpn.coroutines.DefaultApplicationScopeProvider
import com.onthecrow.deltavpn.coroutines.DefaultDispatchersProvider
import com.onthecrow.deltavpn.coroutines.DispatchersProvider
import org.koin.dsl.module

val coroutinesModule = module {
    single<DispatchersProvider> { DefaultDispatchersProvider }
    single<ApplicationScopeProvider> { DefaultApplicationScopeProvider(get()) }
}
