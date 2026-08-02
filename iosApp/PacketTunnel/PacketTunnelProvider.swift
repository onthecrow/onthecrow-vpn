import NetworkExtension
import DeltaTunnel

/// The Network Extension principal class (NSExtensionPrincipalClass = PacketTunnel.PacketTunnelProvider).
///
/// It is intentionally a *Swift* subclass of NEPacketTunnelProvider: Swift/Obj-C classes are
/// registered with the Obj-C runtime at image load, so `NSClassFromString` finds it the instant
/// the extension process launches. All real logic lives in Kotlin (`DeltaTunnelCore` in the
/// DeltaTunnel framework); this class just forwards the two lifecycle callbacks to it.
class PacketTunnelProvider: NEPacketTunnelProvider {

    private var core: DeltaTunnelCore?

    override func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        let c = DeltaTunnelCore(provider: self) { msg in
            #if DEBUG
            NSLog("DeltaTunnel: %@", msg)
            #endif
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
