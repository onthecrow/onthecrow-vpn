package com.onthecrow.onthecrowvpn.di

import com.onthecrow.onthecrowvpn.connection.ConnectionDestination
import com.onthecrow.onthecrowvpn.connection.di.connectionLogicModule
import com.onthecrow.onthecrowvpn.connection.di.connectionModule
import com.onthecrow.onthecrowvpn.coroutines.di.coroutinesModule
import com.onthecrow.onthecrowvpn.datastore.di.datastoreModule
import com.onthecrow.onthecrowvpn.navigation.Destination
import com.onthecrow.onthecrowvpn.navigation.di.StartDestination
import com.onthecrow.onthecrowvpn.navigation.di.navigationModule
import com.onthecrow.onthecrowvpn.vpn.di.vpnModule
import com.onthecrow.onthecrowvpn.xray.di.xrayModule
import kotlinx.serialization.json.Json
import org.koin.dsl.bind
import org.koin.dsl.module

val applicationModule = module {
    single { Json { ignoreUnknownKeys = true } }
    single(StartDestination) { ConnectionDestination } bind Destination::class
    includes(
        coroutinesModule,
        datastoreModule,
        navigationModule,
        xrayModule,
        vpnModule,
        connectionLogicModule,
        connectionModule,
    )
}
