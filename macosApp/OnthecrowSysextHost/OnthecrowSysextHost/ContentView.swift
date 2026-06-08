import SwiftUI
import SystemExtensions

struct ContentView: View {
    var body: some View {
        Button("Install / Activate Extension") {
            let req = OSSystemExtensionRequest.activationRequest(
                forExtensionWithIdentifier: "com.onthecrow.onthecrowvpn.SystemExtension",
                queue: .main
            )
            req.delegate = Delegate.shared
            OSSystemExtensionManager.shared.submitRequest(req)
        }
        .padding(40)
    }
}

final class Delegate: NSObject, OSSystemExtensionRequestDelegate {
    static let shared = Delegate()
    func request(_ r: OSSystemExtensionRequest, didFinishWithResult result: OSSystemExtensionRequest.Result) {
        NSLog("sysext result: \(result.rawValue)")
    }
    func request(_ r: OSSystemExtensionRequest, didFailWithError error: Error) {
        NSLog("sysext failed: \(error)")
    }
    func requestNeedsUserApproval(_ r: OSSystemExtensionRequest) {
        NSLog("sysext needs approval — open System Settings")
    }
    func request(_ r: OSSystemExtensionRequest, actionForReplacingExtension existing: OSSystemExtensionProperties, withExtension ext: OSSystemExtensionProperties) -> OSSystemExtensionRequest.ReplacementAction {
        .replace
    }
}
