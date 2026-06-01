package com.onthecrow.onthecrowvpn.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.onthecrow.onthecrowvpn.coroutines.DispatchersProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import platform.Foundation.NSHomeDirectory

internal class IOSDataStoreFactory(
    private val dispatchersProvider: DispatchersProvider,
) : DataStoreFactory {
    override fun createPreferencesDataStore(name: String): DataStore<Preferences> {
        return createPreferencesDataStoreWithPath(
            scope = CoroutineScope(SupervisorJob() + dispatchersProvider.io),
            producePath = { NSHomeDirectory() + "/Documents/$name" },
        )
    }
}
