package com.onthecrow.deltavpn.datastore.di

import com.onthecrow.deltavpn.datastore.AndroidDataStoreFactory
import com.onthecrow.deltavpn.datastore.DataStoreFactory
import org.koin.dsl.module

internal actual val datastorePlatformModule = module {
    single<DataStoreFactory> { AndroidDataStoreFactory(get(), get()) }
}
