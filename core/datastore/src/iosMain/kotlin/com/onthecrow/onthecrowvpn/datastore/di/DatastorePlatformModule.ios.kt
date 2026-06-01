package com.onthecrow.onthecrowvpn.datastore.di

import com.onthecrow.onthecrowvpn.datastore.DataStoreFactory
import com.onthecrow.onthecrowvpn.datastore.IOSDataStoreFactory
import org.koin.dsl.module

internal actual val datastorePlatformModule = module {
    single<DataStoreFactory> { IOSDataStoreFactory(get()) }
}
