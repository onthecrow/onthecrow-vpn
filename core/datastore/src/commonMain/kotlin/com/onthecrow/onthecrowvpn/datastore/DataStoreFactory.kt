package com.onthecrow.onthecrowvpn.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import okio.Path.Companion.toPath

interface DataStoreFactory {
    fun createPreferencesDataStore(name: String): DataStore<Preferences>
}

internal fun createPreferencesDataStoreWithPath(
    scope: CoroutineScope,
    producePath: () -> String,
): DataStore<Preferences> {
    return PreferenceDataStoreFactory.createWithPath(
        scope = scope,
        produceFile = { producePath().toPath() },
    )
}
