package com.privatevpn.app.ui

internal class ProfileSwitchRequestQueue {
    private var pendingProfileId: String? = null

    @Synchronized
    fun offer(profileId: String) {
        pendingProfileId = profileId
    }

    @Synchronized
    fun takeLatest(): String? {
        return pendingProfileId.also { pendingProfileId = null }
    }

    @Synchronized
    fun clear() {
        pendingProfileId = null
    }
}
