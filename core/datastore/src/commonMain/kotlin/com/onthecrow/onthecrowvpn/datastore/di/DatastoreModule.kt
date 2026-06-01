package com.onthecrow.onthecrowvpn.datastore.di

import org.koin.dsl.module

val datastoreModule = module {
    includes(datastorePlatformModule)
}
