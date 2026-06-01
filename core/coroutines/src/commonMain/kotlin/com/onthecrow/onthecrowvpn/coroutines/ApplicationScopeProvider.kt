package com.onthecrow.onthecrowvpn.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

interface ApplicationScopeProvider {
    val scope: CoroutineScope
}

class DefaultApplicationScopeProvider(
    dispatchersProvider: DispatchersProvider,
) : ApplicationScopeProvider {
    override val scope = CoroutineScope(SupervisorJob() + dispatchersProvider.default)
}
