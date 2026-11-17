package com.privatevpn.app.profiles.subscriptions

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionUrlValidatorTest {
    @Test fun `preserves exact case encoding slash and query`() {
        val url = "HTTPS://example.com:443/sub/AbC%2fD?x=a+b&y=%2F%2f"
        assertEquals(url, SubscriptionUrlValidator.validate(url))
    }
    @Test(expected = IllegalArgumentException::class) fun `rejects embedded credentials`() {
        SubscriptionUrlValidator.validate("https://user:pass@example.com/sub")
    }
    @Test(expected = IllegalArgumentException::class) fun `rejects malformed percent encoding`() {
        SubscriptionUrlValidator.validate("https://example.com/sub/%ZZ")
    }
    @Test(expected = IllegalArgumentException::class) fun `rejects URL fragment`() {
        SubscriptionUrlValidator.validate("https://example.com/sub#token")
    }
    @Test(expected = IllegalArgumentException::class) fun `rejects interior newline`() {
        SubscriptionUrlValidator.validate("https://example.com/sub/abc\ndef")
    }
}
