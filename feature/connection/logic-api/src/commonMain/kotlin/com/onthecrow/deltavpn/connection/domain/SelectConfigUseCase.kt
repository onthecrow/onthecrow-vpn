package com.onthecrow.deltavpn.connection.domain

import com.onthecrow.deltavpn.connection.model.ConfigRef

fun interface SelectConfigUseCase {
    suspend operator fun invoke(ref: ConfigRef?)
}
