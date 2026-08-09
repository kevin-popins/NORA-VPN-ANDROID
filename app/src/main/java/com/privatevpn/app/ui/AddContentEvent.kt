package com.privatevpn.app.ui

sealed interface AddedServerTarget {
    data class Profile(val id: String) : AddedServerTarget
    data class Subscription(val id: String) : AddedServerTarget
}

sealed interface AddContentEvent {
    data class Success(
        val target: AddedServerTarget,
        val message: String
    ) : AddContentEvent

    data class Failure(val message: String) : AddContentEvent
}
