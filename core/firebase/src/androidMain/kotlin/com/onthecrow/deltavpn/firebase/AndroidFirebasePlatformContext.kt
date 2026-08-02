package com.onthecrow.deltavpn.firebase

import android.app.Application

interface AndroidFirebasePlatformContext : FirebasePlatformContext {
    val firebaseApplication: Application
}
