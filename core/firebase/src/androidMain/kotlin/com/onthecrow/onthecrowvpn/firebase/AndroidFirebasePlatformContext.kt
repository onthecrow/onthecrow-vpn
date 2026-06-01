package com.onthecrow.onthecrowvpn.firebase

import android.app.Application

interface AndroidFirebasePlatformContext : FirebasePlatformContext {
    val firebaseApplication: Application
}
