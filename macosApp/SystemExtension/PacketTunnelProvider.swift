import NetworkExtension
import OnthecrowTunnel

/// Principal class of the macOS NetworkExtension **system extension** (a packet-tunnel provider).
///
/// Like on iOS, this is intentionally a *Swift* subclass of `NEPacketTunnelProvider`: the system
/// resolves the provider class via the Obj-C runtime at extension-process launch, before any
/// Kotlin/Native runtime has initialized, so a KN subclass would not be found. All real logic lives
/// in Kotlin (`OnthecrowTunnelCore`, reused verbatim from iOS via the shared `appleMain` source set);
/// this class just forwards the two lifecycle callbacks to it.
///
/// The macOS system extension and the JVM app share the App Group
/// `group.com.onthecrow.onthecrowvpn`, through which the core publishes a human-readable failure
/// reason that the app surfaces.
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
