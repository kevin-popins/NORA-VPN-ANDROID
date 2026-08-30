package com.privatevpn.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri

const val NORA_TELEGRAM_BOT_URL: String = "https://t.me/noravpnrobertsonbot"

fun openNoraTelegramBot(context: Context) {
    val telegramIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("tg://resolve?domain=noravpnrobertsonbot")
    )
    if (runCatching { context.startActivity(telegramIntent) }.isSuccess) return

    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(NORA_TELEGRAM_BOT_URL)))
}
