package com.onthecrow.onthecrowvpn.connection.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.onthecrow.onthecrowvpn.connection.ObserveSavedConnectionConfigUseCaseImpl
import com.onthecrow.onthecrowvpn.connection.SaveConnectionConfigUseCaseImpl
import com.onthecrow.onthecrowvpn.connection.ValidateConnectionConfigUseCaseImpl
import com.onthecrow.onthecrowvpn.connection.data.ConnectionConfigRepositoryImpl
import com.onthecrow.onthecrowvpn.connection.data.datastore.ConnectionConfigPreferencesDataSource
import com.onthecrow.onthecrowvpn.connection.domain.ConnectionConfigRepository
import com.onthecrow.onthecrowvpn.connection.domain.ObserveSavedConnectionConfigUseCase
import com.onthecrow.onthecrowvpn.connection.domain.SaveConnectionConfigUseCase
import com.onthecrow.onthecrowvpn.connection.domain.ValidateConnectionConfigUseCase
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
        )
    }
    single { ConnectionConfigRepositoryImpl(get()) } bind ConnectionConfigRepository::class
    single { ObserveSavedConnectionConfigUseCaseImpl(get()) } bind ObserveSavedConnectionConfigUseCase::class
    single { SaveConnectionConfigUseCaseImpl(get()) } bind SaveConnectionConfigUseCase::class
    single { ValidateConnectionConfigUseCaseImpl(get(), get()) } bind ValidateConnectionConfigUseCase::class
}
