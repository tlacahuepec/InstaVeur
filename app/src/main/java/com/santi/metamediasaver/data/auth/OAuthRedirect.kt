package com.santi.metamediasaver.data.auth

import java.net.URLDecoder

/**
 * Parsed result of an OAuth redirect URI delivered to the app via a deep link.
 *
 * Pure-JVM so it can be unit-tested without an Android Uri instance.
 */
sealed interface OAuthRedirect {
    data class Success(val code: String, val state: String) : OAuthRedirect
    data class Error(val message: String) : OAuthRedirect
    object Malformed : OAuthRedirect
}

object OAuthRedirectParser {
    fun parse(uri: String): OAuthRedirect {
        val query = uri.substringAfter('?', missingDelimiterValue = "")
        if (query.isEmpty()) return OAuthRedirect.Malformed

        val params = HashMap<String, String>()
        for (part in query.split('&')) {
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            val key = if (eq < 0) part else part.substring(0, eq)
            val value = if (eq < 0) "" else part.substring(eq + 1)
            params[decode(key)] = decode(value)
        }

        val errorDescription = params["error_description"]?.takeIf { it.isNotBlank() }
        val error = params["error"]?.takeIf { it.isNotBlank() }
        if (errorDescription != null || error != null) {
            return OAuthRedirect.Error(errorDescription ?: error!!)
        }

        val code = params["code"]?.takeIf { it.isNotBlank() }
        val state = params["state"]?.takeIf { it.isNotBlank() }
        if (code == null || state == null) return OAuthRedirect.Malformed

        return OAuthRedirect.Success(code, state)
    }

    private fun decode(value: String): String =
        try {
            URLDecoder.decode(value, "UTF-8")
        } catch (_: IllegalArgumentException) {
            value
        }
}
