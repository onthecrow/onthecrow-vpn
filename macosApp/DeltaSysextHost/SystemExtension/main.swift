//
//  main.swift
//  SystemExtension
//
//  Created by Dmitry Voronov on 06.06.2026.
//

import Foundation
import NetworkExtension

autoreleasepool {
    NEProvider.startSystemExtensionMode()
}

dispatchMain()
