package com.privatevpn.app.profiles.subscriptions

/** Public failure categories contain no provider URL, response, identity or credentials. */
enum class SubscriptionImportFailure { INVALID_INPUT, VALIDATION_FAILED, DISABLED, STORAGE_ERROR }

class SubscriptionImportException(val reason: SubscriptionImportFailure) :
    IllegalArgumentException("Не удалось подтвердить подписку.")
