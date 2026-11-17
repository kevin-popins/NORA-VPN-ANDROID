package com.privatevpn.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

const val NORA_PURCHASE_WEBSITE_URL: String = "https://nora.rest/"

fun openNoraPurchaseWebsite(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(NORA_PURCHASE_WEBSITE_URL)).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (runCatching { context.startActivity(intent) }.isFailure) {
        Toast.makeText(context, "Не удалось открыть сайт NORA", Toast.LENGTH_SHORT).show()
    }
}
