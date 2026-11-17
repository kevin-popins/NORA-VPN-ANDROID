package com.privatevpn.app.profiles.importer

import com.privatevpn.app.profiles.deeplink.NoraDeepLinkImport
import com.privatevpn.app.profiles.deeplink.NoraDeepLinkParser
import com.privatevpn.app.profiles.model.ImportedProfileDraft
import com.privatevpn.app.profiles.subscriptions.HappCrypt5Decryptor
import com.privatevpn.app.profiles.subscriptions.HappRoutingCompat
import com.privatevpn.app.profiles.subscriptions.HappRoutingProfile
import com.privatevpn.app.profiles.subscriptions.SubscriptionParser
import com.privatevpn.app.profiles.subscriptions.SubscriptionUrlValidator

sealed interface ProfileInput {
    data class Subscription(val url: String, val displayName: String?) : ProfileInput
    data class Profiles(val drafts: List<ImportedProfileDraft>) : ProfileInput
    data class Routing(val profile: HappRoutingProfile) : ProfileInput
}

/** One classifier for typed, pasted, file, shared and cold/warm deep-link input. */
class ProfileInputParser(
    private val subscriptionParser: SubscriptionParser = SubscriptionParser(ProfileImportParser()),
    private val decryptCrypt5: (String) -> String = HappCrypt5Decryptor::decryptToText
) {
    fun parse(rawInput: String, displayName: String? = null): ProfileInput = parseInput(rawInput, displayName, 0)

    private fun parseInput(rawInput: String, displayName: String?, depth: Int): ProfileInput {
        require(depth <= 4) { "Слишком много вложенных ссылок импорта." }
        val input = ImportInputNormalizer.normalize(rawInput)
        require(input.isNotBlank()) { "Пустой профиль или ссылка подписки." }
        require(input.none { it == '\u0000' || (it < ' ' && it !in "\r\n\t") }) {
            "В данных импорта есть недопустимые управляющие символы."
        }
        if (NoraDeepLinkParser.isNoraDeepLink(input)) {
            return when (val request = NoraDeepLinkParser.parse(input)) {
                is NoraDeepLinkImport.Subscription -> ProfileInput.Subscription(request.url, request.displayName ?: displayName)
                is NoraDeepLinkImport.Profile -> parseInput(request.payload, displayName, depth + 1).also {
                    require(it !is ProfileInput.Subscription) { "Ссылка профиля содержит URL подписки." }
                }
            }
        }
        if (HappCrypt5Decryptor.isCrypt5Link(input)) {
            return parseInput(decryptCrypt5(input), displayName, depth + 1)
        }
        if (input.startsWith("https://", true) || input.startsWith("http://", true)) {
            return ProfileInput.Subscription(SubscriptionUrlValidator.validate(input), displayName?.trim()?.takeIf { it.isNotEmpty() })
        }
        val parsed = subscriptionParser.parse(input)
        if (parsed.validProfiles.isNotEmpty()) {
            val routing = parsed.happRoutingProfile
            return ProfileInput.Profiles(parsed.validProfiles.map { draft ->
                routing?.let { HappRoutingCompat.applyRoutingToDraft(draft, it) } ?: draft
            })
        }
        parsed.happRoutingProfile?.let { return ProfileInput.Routing(it) }
        throw IllegalArgumentException("Неподдерживаемый формат профиля или ссылки подписки.")
    }
}
