package com.onthecrow.deltavpn.datastore.di

import com.onthecrow.deltavpn.datastore.DataStoreFactory
import com.onthecrow.deltavpn.datastore.JvmDataStoreFactory
import org.koin.dsl.module

internal actual val datastorePlatformModule = module {
    single<DataStoreFactory> { JvmDataStoreFactory(get()) }
}
