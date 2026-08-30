package com.privatevpn.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileSwitchRequestQueueTest {
    @Test
    fun `rapid selections keep only latest profile`() {
        val queue = ProfileSwitchRequestQueue()

        queue.offer("server-a")
        queue.offer("server-b")
        queue.offer("server-c")

        assertEquals("server-c", queue.takeLatest())
        assertNull(queue.takeLatest())
    }

    @Test
    fun `clear removes pending switch before disconnect`() {
        val queue = ProfileSwitchRequestQueue()
        queue.offer("server-a")

        queue.clear()

        assertNull(queue.takeLatest())
    }
}
