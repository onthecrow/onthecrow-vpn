package com.onthecrow.onthecrowvpn.connection.domain

fun interface SelectConfigUseCase {
    suspend operator fun invoke(configId: String)
}
