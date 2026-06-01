package com.onthecrow.onthecrowvpn.xray

import android.content.Context

object AndroidXrayEnvironment {
    lateinit var applicationContext: Context
        private set

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }
}
