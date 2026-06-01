package com.onthecrow.onthecrowvpn.connection.domain

fun interface LoadBundleUseCase {
    suspend operator fun invoke(id: String)
}
