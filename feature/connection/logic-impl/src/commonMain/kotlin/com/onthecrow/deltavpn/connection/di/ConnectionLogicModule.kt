package com.onthecrow.deltavpn.connection.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.onthecrow.deltavpn.connection.AddSourceUseCaseImpl
import com.onthecrow.deltavpn.connection.CollapsedGroupsUseCaseImpl
import com.onthecrow.deltavpn.connection.ConfigSourcesOrchestrator
import com.onthecrow.deltavpn.connection.ObserveConfigSourcesUseCaseImpl
import com.onthecrow.deltavpn.connection.PrepareConnectionConfigUseCaseImpl
import com.onthecrow.deltavpn.connection.RefreshSourceUseCaseImpl
import com.onthecrow.deltavpn.connection.RemoveSourceUseCaseImpl
import com.onthecrow.deltavpn.connection.SelectConfigUseCaseImpl
import com.onthecrow.deltavpn.connection.VpnSyncWorker
import com.onthecrow.deltavpn.connection.data.ConfigSourcesRepositoryImpl
import com.onthecrow.deltavpn.connection.data.RecoveryTuningRepositoryImpl
import com.onthecrow.deltavpn.connection.data.SplitTunnelRepositoryImpl
import com.onthecrow.deltavpn.connection.data.VpnConsentRepositoryImpl
import com.onthecrow.deltavpn.connection.data.datastore.ConfigSourcesPreferencesDataSource
import com.onthecrow.deltavpn.connection.data.subscription.SubscriptionUrlFetcher
import com.onthecrow.deltavpn.vpn.domain.RecoveryTuningRepository
import com.onthecrow.deltavpn.vpn.domain.SplitTunnelRepository
import com.onthecrow.deltavpn.connection.domain.AddSourceUseCase
import com.onthecrow.deltavpn.connection.domain.CollapsedGroupsUseCase
import com.onthecrow.deltavpn.connection.domain.ConfigSourcesRepository
import com.onthecrow.deltavpn.connection.domain.ObserveConfigSourcesUseCase
import com.onthecrow.deltavpn.connection.domain.PrepareConnectionConfigUseCase
import com.onthecrow.deltavpn.connection.domain.RefreshSourceUseCase
import com.onthecrow.deltavpn.connection.domain.RemoveSourceUseCase
import com.onthecrow.deltavpn.connection.domain.SelectConfigUseCase
import com.onthecrow.deltavpn.connection.domain.VpnConsentRepository
import com.onthecrow.deltavpn.datastore.DataStoreFactory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

private const val CONNECTION_CONFIG_DATA_STORE_QUALIFIER = "connection_config_data_store"
private const val CONNECTION_CONFIG_DATA_STORE_NAME = "connection_config.preferences_pb"
private const val VPN_SETTINGS_DATA_STORE_QUALIFIER = "vpn_settings_data_store"
private const val VPN_SETTINGS_DATA_STORE_NAME = "vpn_settings.preferences_pb"
private const val SUBSCRIPTION_FETCH_TIMEOUT_MS = 15_000L

val connectionLogicModule = module {
    single<DataStore<Preferences>>(named(CONNECTION_CONFIG_DATA_STORE_QUALIFIER)) {
        get<DataStoreFactory>().createPreferencesDataStore(CONNECTION_CONFIG_DATA_STORE_NAME)
    }
    single<DataStore<Preferences>>(named(VPN_SETTINGS_DATA_STORE_QUALIFIER)) {
        get<DataStoreFactory>().createPreferencesDataStore(VPN_SETTINGS_DATA_STORE_NAME)
    }
    single {
        SplitTunnelRepositoryImpl(get(named(VPN_SETTINGS_DATA_STORE_QUALIFIER)), get(), get())
    } bind SplitTunnelRepository::class
    single {
        VpnConsentRepositoryImpl(get(named(VPN_SETTINGS_DATA_STORE_QUALIFIER)))
    } bind VpnConsentRepository::class
    single {
        RecoveryTuningRepositoryImpl(get(named(VPN_SETTINGS_DATA_STORE_QUALIFIER)))
    } bind RecoveryTuningRepository::class
    single {
        ConfigSourcesPreferencesDataSource(
            dataStore = get(named(CONNECTION_CONFIG_DATA_STORE_QUALIFIER)),
            json = get(),
            errorReporter = get(),
        )
    }
    single { ConfigSourcesRepositoryImpl(get()) } bind ConfigSourcesRepository::class
    single {
        // Engine auto-discovered: OkHttp on Android/JVM, Darwin on iOS.
        HttpClient {
            install(HttpTimeout) {
                requestTimeoutMillis = SUBSCRIPTION_FETCH_TIMEOUT_MS
            }
        }
    }
    single { SubscriptionUrlFetcher(get(), get(), get(), get()) }
    single { ConfigSourcesOrchestrator(get(), get(), get()) }
    single { ObserveConfigSourcesUseCaseImpl(get()) } bind ObserveConfigSourcesUseCase::class
    single { CollapsedGroupsUseCaseImpl(get()) } bind CollapsedGroupsUseCase::class
    single { AddSourceUseCaseImpl(get(), get(), get(), get(), get(), get()) } bind AddSourceUseCase::class
    single { RemoveSourceUseCaseImpl(get(), get(), get()) } bind RemoveSourceUseCase::class
    single { RefreshSourceUseCaseImpl(get(), get(), get(), get()) } bind RefreshSourceUseCase::class
    single { SelectConfigUseCaseImpl(get()) } bind SelectConfigUseCase::class
    single { PrepareConnectionConfigUseCaseImpl(get()) } bind PrepareConnectionConfigUseCase::class
    single(createdAtStart = true) { VpnSyncWorker(get(), get(), get(), get(), get(), get()) }
}
