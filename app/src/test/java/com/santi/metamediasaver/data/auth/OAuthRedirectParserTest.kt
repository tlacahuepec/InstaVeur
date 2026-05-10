package com.santi.metamediasaver.data.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class OAuthRedirectParserTest {
    @Test
    fun success_redirect_returns_code_and_state() {
        val result = OAuthRedirectParser.parse(
            "metamediasaver://oauth/meta?code=abc&state=xyz"
        )
        assertEquals(OAuthRedirect.Success(code = "abc", state = "xyz"), result)
    }

    @Test
    fun user_denied_redirect_uses_error_description() {
        val result = OAuthRedirectParser.parse(
            "metamediasaver://oauth/meta?error=access_denied&error_description=User+denied"
        )
        assertEquals(OAuthRedirect.Error("User denied"), result)
    }

    @Test
    fun error_without_description_falls_back_to_error_code() {
        val result = OAuthRedirectParser.parse(
            "metamediasaver://oauth/meta?error=access_denied"
        )
        assertEquals(OAuthRedirect.Error("access_denied"), result)
    }

    @Test
    fun missing_code_is_malformed() {
        val result = OAuthRedirectParser.parse(
            "metamediasaver://oauth/meta?state=xyz"
        )
        assertEquals(OAuthRedirect.Malformed, result)
    }

    @Test
    fun missing_state_is_malformed() {
        val result = OAuthRedirectParser.parse(
            "metamediasaver://oauth/meta?code=abc"
        )
        assertEquals(OAuthRedirect.Malformed, result)
    }

    @Test
    fun no_query_is_malformed() {
        val result = OAuthRedirectParser.parse("metamediasaver://oauth/meta")
        assertEquals(OAuthRedirect.Malformed, result)
    }

    @Test
    fun percent_encoded_values_are_decoded() {
        val result = OAuthRedirectParser.parse(
            "metamediasaver://oauth/meta?error=denied&error_description=needs%20review"
        )
        assertEquals(OAuthRedirect.Error("needs review"), result)
    }
}
