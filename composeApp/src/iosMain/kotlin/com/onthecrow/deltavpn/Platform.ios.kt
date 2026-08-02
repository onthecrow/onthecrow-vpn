package com.onthecrow.deltavpn

import org.koin.dsl.module
import platform.UIKit.UIDevice

class IOSPlatform : Platform {
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
    override val platformModule = module {}
}
