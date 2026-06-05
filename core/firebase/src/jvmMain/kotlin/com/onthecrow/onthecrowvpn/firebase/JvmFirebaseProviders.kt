package com.onthecrow.onthecrowvpn.firebase.di

import com.onthecrow.onthecrowvpn.firebase.AnalyticsTracker
import com.onthecrow.onthecrowvpn.firebase.CrashReporter

internal actual fun createAnalyticsTracker(): AnalyticsTracker = StdoutAnalyticsTracker

internal actual fun createCrashReporter(): CrashReporter = StdoutCrashReporter

private object StdoutAnalyticsTracker : AnalyticsTracker {
    override fun logEvent(name: String, parameters: Map<String, String>) {
        if (parameters.isEmpty()) {
            println("[Analytics] $name")
        } else {
            println("[Analytics] $name $parameters")
        }
    }

    override fun setUserId(userId: String?) {
        println("[Analytics] setUserId=$userId")
    }

    override fun setUserProperty(name: String, value: String?) {
        println("[Analytics] setUserProperty $name=$value")
    }
}

private object StdoutCrashReporter : CrashReporter {
    override fun log(message: String) {
        println("[Crash] log: $message")
    }

    override fun recordException(throwable: Throwable) {
        println("[Crash] exception: ${throwable.message}")
        throwable.printStackTrace(System.out)
    }

    override fun setUserId(userId: String?) {
        println("[Crash] setUserId=$userId")
    }

    override fun setKey(key: String, value: String) {
        println("[Crash] setKey $key=$value")
    }
}
