package com.onthecrow.onthecrowvpn.connection.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.onthecrow.onthecrowvpn.connection.ActiveBundleOrchestrator
import com.onthecrow.onthecrowvpn.connection.LoadBundleUseCaseImpl
import com.onthecrow.onthecrowvpn.connection.ObserveActiveBundleUseCaseImpl
import com.onthecrow.onthecrowvpn.connection.PrepareConnectionConfigUseCaseImpl
import com.onthecrow.onthecrowvpn.connection.SelectConfigUseCaseImpl
import com.onthecrow.onthecrowvpn.connection.VpnSyncWorker
import com.onthecrow.onthecrowvpn.connection.data.BundleRepositoryImpl
import com.onthecrow.onthecrowvpn.connection.data.datastore.ConnectionConfigPreferencesDataSource
import com.onthecrow.onthecrowvpn.connection.domain.BundleRepository
import com.onthecrow.onthecrowvpn.connection.domain.LoadBundleUseCase
import com.onthecrow.onthecrowvpn.connection.domain.ObserveActiveBundleUseCase
import com.onthecrow.onthecrowvpn.connection.domain.PrepareConnectionConfigUseCase
import com.onthecrow.onthecrowvpn.connection.domain.SelectConfigUseCase
import com.onthecrow.onthecrowvpn.datastore.DataStoreFactory
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

private const val CONNECTION_CONFIG_DATA_STORE_QUALIFIER = "connection_config_data_store"
private const val CONNECTION_CONFIG_DATA_STORE_NAME = "connection_config.preferences_pb"

val connectionLogicModule = module {
    single<DataStore<Preferences>>(named(CONNECTION_CONFIG_DATA_STORE_QUALIFIER)) {
        get<DataStoreFactory>().createPreferencesDataStore(CONNECTION_CONFIG_DATA_STORE_NAME)
    }
    single {
        ConnectionConfigPreferencesDataSource(
            dataStore = get(named(CONNECTION_CONFIG_DATA_STORE_QUALIFIER)),
            json = get(),
        )
    }
    single { BundleRepositoryImpl(get()) } bind BundleRepository::class
    single { ActiveBundleOrchestrator(get(), get(), get()) }
    single { ObserveActiveBundleUseCaseImpl(get()) } bind ObserveActiveBundleUseCase::class
    single { LoadBundleUseCaseImpl(get()) } bind LoadBundleUseCase::class
    single { SelectConfigUseCaseImpl(get()) } bind SelectConfigUseCase::class
    single { PrepareConnectionConfigUseCaseImpl(get()) } bind PrepareConnectionConfigUseCase::class
    single(createdAtStart = true) { VpnSyncWorker(get(), get(), get(), get()) }
}
