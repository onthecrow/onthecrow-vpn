package com.onthecrow.onthecrowvpn.datastore.di

import com.onthecrow.onthecrowvpn.datastore.AndroidDataStoreFactory
import com.onthecrow.onthecrowvpn.datastore.DataStoreFactory
import org.koin.dsl.module

internal actual val datastorePlatformModule = module {
    single<DataStoreFactory> { AndroidDataStoreFactory(get(), get()) }
}
