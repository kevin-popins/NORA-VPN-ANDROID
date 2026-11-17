package com.privatevpn.app.profiles.subscriptions

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionContentAcceptanceTest {
    @Test fun `technical only response is accepted as display entries rather than real transport`() {
        assertEquals(SubscriptionContentAcceptance.TECHNICAL_ENTRIES,
            SubscriptionContentAcceptance.classify(validatedDisplayBody = true, connectableProfilesCount = 0, technicalEntriesCount = 1))
    }

    @Test fun `real and mixed responses retain fresh profile category`() {
        assertEquals(SubscriptionContentAcceptance.FRESH_PROFILES,
            SubscriptionContentAcceptance.classify(validatedDisplayBody = true, connectableProfilesCount = 2, technicalEntriesCount = 0))
        assertEquals(SubscriptionContentAcceptance.FRESH_PROFILES,
            SubscriptionContentAcceptance.classify(validatedDisplayBody = true, connectableProfilesCount = 2, technicalEntriesCount = 1))
    }

    @Test fun `empty unknown or invalid response cannot become accepted content`() {
        assertEquals(SubscriptionContentAcceptance.NONE,
            SubscriptionContentAcceptance.classify(validatedDisplayBody = true, connectableProfilesCount = 0, technicalEntriesCount = 0))
        assertEquals(SubscriptionContentAcceptance.NONE,
            SubscriptionContentAcceptance.classify(validatedDisplayBody = false, connectableProfilesCount = 2, technicalEntriesCount = 1))
    }
}
