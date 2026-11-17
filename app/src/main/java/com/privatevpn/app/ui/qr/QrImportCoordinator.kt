package com.privatevpn.app.ui.qr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns QR session identity and import completion, independent of a camera or navigation entry.
 * The injected importer must return only after strict validation and persistence have completed.
 */
class QrImportCoordinator(
    private val scope: CoroutineScope,
    private val importer: suspend (String) -> Unit
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow<QrImportState>(QrImportState.Idle)
    val state: StateFlow<QrImportState> = mutableState.asStateFlow()

    private var nextSession = 0L
    private var activeScan: Long? = null
    private var scanConsumed = false
    private var generation = 0L
    private var importJob: Job? = null

    /** Starting a new scan supersedes previous scan/import work and its eventual outcome. */
    fun beginScan(): Long {
        val (token, previousJob) = synchronized(lock) {
            generation += 1
            val previous = importJob
            importJob = null
            nextSession += 1
            activeScan = nextSession
            scanConsumed = false
            mutableState.value = QrImportState.Idle
            nextSession to previous
        }
        previousJob?.cancel()
        return token
    }

    /** Returns true once for the current session, including when the importer completes inline. */
    fun submit(sessionToken: Long, raw: String): Boolean {
        val (previousJob, acceptedJob) = synchronized(lock) {
            if (activeScan != sessionToken || scanConsumed) return false
            scanConsumed = true
            generation += 1
            val acceptedGeneration = generation
            val previous = importJob
            mutableState.value = QrImportState.Loading
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    importer(raw)
                    publish(acceptedGeneration, QrImportState.Success)
                } catch (_: CancellationException) {
                    publish(acceptedGeneration, QrImportState.Idle)
                } catch (error: Exception) {
                    val code = (error as? QrImportException)?.code ?: QrImportFailureCode.UNSUPPORTED
                    publish(acceptedGeneration, QrImportState.Failure(code.safeMessage))
                } finally {
                    synchronized(lock) {
                        if (generation == acceptedGeneration) importJob = null
                    }
                }
            }
            importJob = job
            previous to job
        }
        previousJob?.cancel()
        acceptedJob.start()
        return true
    }

    /** Stops late camera callbacks; an already accepted import continues to completion. */
    fun closeScan(sessionToken: Long) {
        synchronized(lock) {
            if (activeScan == sessionToken) {
                activeScan = null
                scanConsumed = true
            }
        }
    }

    fun cancelImport() {
        val previousJob = synchronized(lock) {
            generation += 1
            activeScan = null
            scanConsumed = true
            mutableState.value = QrImportState.Idle
            importJob.also { importJob = null }
        }
        previousJob?.cancel()
    }

    fun dismissError() {
        synchronized(lock) {
            if (mutableState.value is QrImportState.Failure) mutableState.value = QrImportState.Idle
        }
    }

    private fun publish(expectedGeneration: Long, outcome: QrImportState) {
        synchronized(lock) {
            if (generation == expectedGeneration) mutableState.value = outcome
        }
    }
}
