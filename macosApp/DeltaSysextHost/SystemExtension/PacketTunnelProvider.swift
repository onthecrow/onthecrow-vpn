//
//  PacketTunnelProvider.swift
//  SystemExtension
//
//  Principal class of the macOS NetworkExtension system extension.
//  It is a thin Swift subclass of NEPacketTunnelProvider (registered with the Obj-C runtime at
//  launch, so NSExtension/NEProviderClasses resolves it); all real logic lives in Kotlin
//  (DeltaTunnelCore, reused verbatim from iOS via the shared appleMain source set).
//

import NetworkExtension
import DeltaTunnel

class PacketTunnelProvider: NEPacketTunnelProvider {

    private var core: DeltaTunnelCore?

    override func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        let c = DeltaTunnelCore(provider: self) { msg in
            NSLog("DeltaTunnel: %@", msg)
        }
        core = c
        c.startTunnel(options: options) { error in
            completionHandler(error)
        }
    }

    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        core?.stopTunnel(reason: Int64(reason.rawValue)) {
            completionHandler()
        }
    }
}
