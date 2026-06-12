package com.onthecrow.onthecrowvpn.vpn

import com.onthecrow.onthecrowvpn.coroutines.ApplicationScopeProvider
import com.onthecrow.onthecrowvpn.vpn.domain.SplitTunnelRepository
import com.onthecrow.onthecrowvpn.vpn.domain.SplitTunnelResolver
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn

/**
 * App-scoped (main process): keeps [AndroidSplitTunnelState] in sync with the persisted settings,
 * resolving them into effective per-app routing. Created at start so the holder is populated before
 * the user can connect.
 */
internal class SplitTunnelAndroidSync(
    private val repository: SplitTunnelRepository,
    scopeProvider: ApplicationScopeProvider,
) {
    init {
        val selfPackage = AndroidVpnEnvironment.applicationContext.packageName
        repository.observe()
            .onEach { settings ->
                val routing = SplitTunnelResolver.resolve(settings, selfPackage)
                AndroidSplitTunnelState.disallow = routing.disallow.toList()
                AndroidSplitTunnelState.allow = routing.allow.toList()
            }
            .launchIn(scopeProvider.scope)
    }
}
