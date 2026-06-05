import NetworkExtension
import OnthecrowTunnel

/// The Network Extension principal class (NSExtensionPrincipalClass = PacketTunnel.PacketTunnelProvider).
///
/// It is intentionally a *Swift* subclass of NEPacketTunnelProvider: Swift/Obj-C classes are
/// registered with the Obj-C runtime at image load, so `NSClassFromString` finds it the instant
/// the extension process launches. All real logic lives in Kotlin (`OnthecrowTunnelCore` in the
/// OnthecrowTunnel framework); this class just forwards the two lifecycle callbacks to it.
class PacketTunnelProvider: NEPacketTunnelProvider {

    private var core: OnthecrowTunnelCore?

    override func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        let c = OnthecrowTunnelCore(provider: self) { msg in
            #if DEBUG
            NSLog("OnthecrowTunnel: %@", msg)
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
