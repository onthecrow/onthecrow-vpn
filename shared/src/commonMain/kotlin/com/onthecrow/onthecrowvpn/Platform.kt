package com.onthecrow.onthecrowvpn

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform