package com.onthecrow.onthecrowvpn.xray.di

import com.onthecrow.onthecrowvpn.xray.XrayEngine
import com.onthecrow.onthecrowvpn.xray.ipc.XrayEngineHolder

/**
 * The app process never talks to libXray directly — see `RemoteXrayEngine` for why. The SAME instance
 * the tunnel uses, so the two do not hold competing bindings to the engine process. `:xray` builds
 * `PlatformXrayEngine` itself and runs no Koin graph, so it never reaches this.
 */
internal actual fun createXrayEngine(): XrayEngine = XrayEngineHolder.engine
