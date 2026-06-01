package com.onthecrow.onthecrowvpn.firebase

interface CrashReporter {
    fun log(message: String)

    fun recordException(throwable: Throwable)

    fun setUserId(userId: String?)

    fun setKey(
        key: String,
        value: String,
    )
}
