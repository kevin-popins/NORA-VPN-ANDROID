package com.privatevpn.app.ui.qr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** Manual input outlives Add navigation entries. Scanned QR data never writes this draft. */
@Stable
class AddImportDraftState(initialText: String = "") {
    var text by mutableStateOf(initialText)

    companion object {
        val Saver: Saver<AddImportDraftState, String> = Saver(
            save = { it.text },
            restore = { AddImportDraftState(it) }
        )
    }
}

@Composable
fun rememberAddImportDraftState(): AddImportDraftState =
    rememberSaveable(saver = AddImportDraftState.Saver) { AddImportDraftState() }
