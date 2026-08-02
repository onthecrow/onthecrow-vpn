package com.onthecrow.deltavpn.vpn

import com.onthecrow.deltavpn.coroutines.ApplicationScopeProvider
import com.onthecrow.deltavpn.vpn.domain.SplitTunnelRepository
import com.onthecrow.deltavpn.vpn.domain.SplitTunnelResolver
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
        val context = AndroidVpnEnvironment.applicationContext
        val routingStore = SplitTunnelRoutingStore(context)
        repository.observe()
            .onEach { settings ->
                val routing = SplitTunnelResolver.resolve(settings, context.packageName)
                AndroidSplitTunnelState.disallow = routing.disallow.toList()
                AndroidSplitTunnelState.allow = routing.allow.toList()
                // Persist for the `:vpn` process, which re-establishes the tunnel on paths this
                // process is not part of (recovery restart, crash self-heal, boot restore) and would
                // otherwise be working from whatever routing was captured at the last connect.
                routingStore.save(routing)
            }
            .launchIn(scopeProvider.scope)
    }
}
