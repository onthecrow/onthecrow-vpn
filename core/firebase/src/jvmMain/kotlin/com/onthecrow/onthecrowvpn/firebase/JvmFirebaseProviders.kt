package com.onthecrow.onthecrowvpn.firebase.di

import com.onthecrow.onthecrowvpn.firebase.AnalyticsTracker
import com.onthecrow.onthecrowvpn.firebase.CrashReporter
import com.onthecrow.onthecrowvpn.firebase.NoOpAnalyticsTracker
import com.onthecrow.onthecrowvpn.firebase.NoOpCrashReporter

internal actual fun createAnalyticsTracker(): AnalyticsTracker = NoOpAnalyticsTracker

internal actual fun createCrashReporter(): CrashReporter = NoOpCrashReporter
