package com.onthecrow.onthecrowvpn.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.onthecrow.onthecrowvpn.coroutines.DispatchersProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.io.File

internal class JvmDataStoreFactory(
    private val dispatchersProvider: DispatchersProvider,
) : DataStoreFactory {
    override fun createPreferencesDataStore(name: String): DataStore<Preferences> {
        return createPreferencesDataStoreWithPath(
            scope = CoroutineScope(SupervisorJob() + dispatchersProvider.io),
            producePath = {
                File(System.getProperty("user.home"), ".onthecrowvpn/$name")
                    .also { it.parentFile?.mkdirs() }
                    .absolutePath
            },
        )
    }
}
