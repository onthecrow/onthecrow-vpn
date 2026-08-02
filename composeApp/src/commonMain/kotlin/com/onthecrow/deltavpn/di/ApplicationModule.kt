package com.onthecrow.deltavpn.di

import com.onthecrow.deltavpn.analytics.di.analyticsModule
import com.onthecrow.deltavpn.errorreporting.di.errorReportingModule
import com.onthecrow.deltavpn.connection.ConnectionDestination
import com.onthecrow.deltavpn.connection.di.connectionLogicModule
import com.onthecrow.deltavpn.connection.di.connectionModule
import com.onthecrow.deltavpn.coroutines.di.coroutinesModule
import com.onthecrow.deltavpn.datastore.di.datastoreModule
import com.onthecrow.deltavpn.firebase.di.firebaseModule
import com.onthecrow.deltavpn.navigation.Destination
import com.onthecrow.deltavpn.navigation.di.StartDestination
import com.onthecrow.deltavpn.navigation.di.navigationModule
import com.onthecrow.deltavpn.settings.di.settingsModule
import com.onthecrow.deltavpn.vpn.di.vpnModule
import com.onthecrow.deltavpn.xray.di.xrayModule
import kotlinx.serialization.json.Json
import org.koin.dsl.bind
import org.koin.dsl.module

val applicationModule = module {
    single { Json { ignoreUnknownKeys = true } }
    single(StartDestination) { ConnectionDestination } bind Destination::class
    includes(
        analyticsModule,
        errorReportingModule,
        coroutinesModule,
        datastoreModule,
        firebaseModule,
        navigationModule,
        xrayModule,
        vpnModule,
        connectionLogicModule,
        connectionModule,
        settingsModule,
    )
}
