package com.onthecrow.onthecrowvpn.firebase.di

import com.onthecrow.onthecrowvpn.firebase.FirestoreClient
import com.onthecrow.onthecrowvpn.firebase.NoOpFirestoreClient

internal actual fun createFirestoreClient(): FirestoreClient = NoOpFirestoreClient
