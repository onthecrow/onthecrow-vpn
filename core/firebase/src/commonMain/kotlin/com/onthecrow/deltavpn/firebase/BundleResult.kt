package com.onthecrow.deltavpn.firebase

import com.onthecrow.deltavpn.connection.model.ConfigBundle

sealed interface BundleResult {
    data class Success(val bundle: ConfigBundle) : BundleResult
    data object NotFound : BundleResult
    data class Error(val message: String) : BundleResult
    data object Unavailable : BundleResult
}
