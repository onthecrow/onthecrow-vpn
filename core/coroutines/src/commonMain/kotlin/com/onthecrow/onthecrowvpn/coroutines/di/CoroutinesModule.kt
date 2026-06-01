package com.onthecrow.onthecrowvpn.coroutines.di

import com.onthecrow.onthecrowvpn.coroutines.ApplicationScopeProvider
import com.onthecrow.onthecrowvpn.coroutines.DefaultApplicationScopeProvider
import com.onthecrow.onthecrowvpn.coroutines.DefaultDispatchersProvider
import com.onthecrow.onthecrowvpn.coroutines.DispatchersProvider
import org.koin.dsl.module

val coroutinesModule = module {
    single<DispatchersProvider> { DefaultDispatchersProvider }
    single<ApplicationScopeProvider> { DefaultApplicationScopeProvider(get()) }
}
