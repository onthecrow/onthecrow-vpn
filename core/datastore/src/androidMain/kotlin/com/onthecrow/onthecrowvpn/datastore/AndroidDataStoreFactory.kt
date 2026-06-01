package com.onthecrow.onthecrowvpn.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.onthecrow.onthecrowvpn.coroutines.DispatchersProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

internal class AndroidDataStoreFactory(
    private val context: Context,
    private val dispatchersProvider: DispatchersProvider,
) : DataStoreFactory {
    override fun createPreferencesDataStore(name: String): DataStore<Preferences> {
        return createPreferencesDataStoreWithPath(
            scope = CoroutineScope(SupervisorJob() + dispatchersProvider.io),
            producePath = { context.filesDir.resolve(name).absolutePath },
        )
    }
}
