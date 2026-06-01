package com.onthecrow.onthecrowvpn.vpn

import android.content.Context

object AndroidVpnEnvironment {
    lateinit var applicationContext: Context
        private set

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }
}
