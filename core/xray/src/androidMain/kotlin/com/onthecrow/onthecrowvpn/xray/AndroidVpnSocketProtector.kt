package com.onthecrow.onthecrowvpn.xray

object AndroidVpnSocketProtector {
    @Volatile
    private var protector: ((Int) -> Boolean)? = null

    fun setProtector(protect: ((Int) -> Boolean)?) {
        protector = protect
    }

    fun protect(fd: Int): Boolean {
        return protector?.invoke(fd) ?: false
    }
}
