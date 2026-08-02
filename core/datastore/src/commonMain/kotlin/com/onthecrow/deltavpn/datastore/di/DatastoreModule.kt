package com.onthecrow.deltavpn.datastore.di

import org.koin.dsl.module

val datastoreModule = module {
    includes(datastorePlatformModule)
}
