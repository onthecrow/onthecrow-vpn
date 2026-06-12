package com.onthecrow.onthecrowvpn.connection.domain

import com.onthecrow.onthecrowvpn.connection.model.ConfigRef

fun interface SelectConfigUseCase {
    suspend operator fun invoke(ref: ConfigRef?)
}
