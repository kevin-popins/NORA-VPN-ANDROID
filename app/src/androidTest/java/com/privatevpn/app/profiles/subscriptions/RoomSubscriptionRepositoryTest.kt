package com.privatevpn.app.profiles.subscriptions

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevpn.app.profiles.db.PrivateVpnDatabase
import com.privatevpn.app.profiles.db.ProfileDao
import com.privatevpn.app.profiles.db.ProfileEntity
import com.privatevpn.app.profiles.db.SubscriptionSourceDao
import com.privatevpn.app.profiles.db.SubscriptionSourceEntity
import com.privatevpn.app.profiles.model.SubscriptionClientPolicy
import com.privatevpn.app.profiles.model.SubscriptionMetadataCodec
import com.privatevpn.app.profiles.model.SubscriptionSyncStatus
import com.privatevpn.app.profiles.repository.toEntity
import com.privatevpn.app.profiles.repository.toDomain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.Base64
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Actual repository/Room refresh with scripted HTTP, fixed synthetic identity and no sockets. */
class RoomSubscriptionRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: PrivateVpnDatabase
    private val requests = Collections.synchronizedList(mutableListOf<Request>())
    private var response: (Request) -> Response = { Response(body = realBody()) }

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, PrivateVpnDatabase::class.java).build()
    }

    @After fun tearDown() { database.close() }

    private fun repository(profileDao: ProfileDao = database.profileDao(), clock: () -> Long = System::currentTimeMillis, sourceDao: SubscriptionSourceDao = database.subscriptionSourceDao()) = RoomSubscriptionRepository(
        database = database,
        appContext = context,
        profileDao = profileDao,
        subscriptionSourceDao = sourceDao,
        clock = clock,
        httpConnectionFactory = { url -> ScriptedConnection(url) { request ->
            requests += request
            response(request)
        } },
        compatibilityHwidProvider = { "synthetic-existing-device" },
        technicalEntryMatcher = { raw, normalized ->
            SubscriptionServiceRecords.isKnownTechnicalEntry(raw, normalized, syntheticLegacyHash)
        }
    )

    @Test fun validatedImportReturnsOnlyAfterRealCommitAndDeduplicatesSameURL() = runBlocking {
        val repository = repository()
        val url = "https://EXAMPLE.invalid:443/sub/Stable?token=AbC%2fD"
        val first = repository.importValidatedSubscription(url, "First name")
        val children = database.profileDao().findBySubscriptionId(first.id)
        assertEquals(2, children.size)
        repository.setLastSelectedProfile(first.id, children.last().id)
        val second = repository.importValidatedSubscription("https://example.invalid/sub/Stable?token=AbC%2fD", "Different name")
        assertEquals(first.id, second.id)
        assertEquals(url, second.sourceUrl)
        assertEquals(first.displayName, second.displayName)
        assertEquals(children.last().id, second.lastSelectedProfileId)
        assertEquals(children.map { it.id }, database.profileDao().findBySubscriptionId(second.id).map { it.id })
        assertEquals(1, database.subscriptionSourceDao().observeAll().first().size)
        assertTrue(requests.all { "if-none-match" !in it.headers })
    }

    @Test fun validatedImportRejectsHTMLAndGenericMarkersWithoutProvisionalRows() = runBlocking {
        val repository = repository()
        val invalidBodies = listOf("<html><body>Connect page</body></html>",
            markerBody.substringBefore('#').replace(syntheticLegacyCredential, "11111111-1111-4111-8111-111111111111") + "#Install%20official%20app")
        for (body in invalidBodies) {
            response = { Response(body = body) }
            try {
                repository.importValidatedSubscription("https://example.invalid/sub/unusable", "Synthetic")
                fail("Unusable subscription must fail")
            } catch (error: SubscriptionImportException) {
                assertEquals(SubscriptionImportFailure.VALIDATION_FAILED, error.reason)
                assertFalse(error.message.orEmpty().contains("example.invalid"))
                assertFalse(error.message.orEmpty().contains("html"))
                assertNull(error.cause)
            }
            assertTrue(database.subscriptionSourceDao().observeAll().first().isEmpty())
            assertTrue(database.profileDao().observeAll().first().isEmpty())
        }
    }

    @Test fun validatedImportFailureLeavesExistingWholeSnapshotUntouched() = runBlocking {
        val repository = repository()
        val source = repository.importValidatedSubscription("https://example.invalid/sub/demo", "Synthetic")
        val children = database.profileDao().findBySubscriptionId(source.id)
        repository.setLastSelectedProfile(source.id, children.last().id)
        val now = System.currentTimeMillis() / 1000
        val committed = SubscriptionClientPolicyReader.read(listOf(metadata(now - 100, 2, true)), source.sourceUrl, source.sourceUrl, 200, now * 1000)!!
        installPolicy(repository, source.id, committed)
        val before = database.subscriptionSourceDao().findById(source.id)!!
        val valid = org.json.JSONArray(realBody()).getJSONObject(0).toString()
        response = { Response(body = "[$valid,\"invalid synthetic entry\"]") }
        try {
            repository.importValidatedSubscription(source.sourceUrl, null)
            fail("Partial body must not validate")
        } catch (error: SubscriptionImportException) {
            assertEquals(SubscriptionImportFailure.VALIDATION_FAILED, error.reason)
        }
        assertEquals(before, database.subscriptionSourceDao().findById(source.id))
        assertEquals(children, database.profileDao().findBySubscriptionId(source.id))
        assertEquals(1, database.subscriptionSourceDao().observeAll().first().size)
    }

    @Test fun failedValidatedQRPreservesConcurrentRenameAndSelectionDuringBodyRead() = runBlocking {
        val repository = repository()
        val source = repository.importValidatedSubscription("https://example.invalid/sub/demo", "Synthetic")
        val children = database.profileDao().findBySubscriptionId(source.id)
        installPolicy(repository, source.id, SubscriptionClientPolicy(`protected` = true, publicLabel = "Public"))
        lateinit var concurrent: SubscriptionSourceEntity
        var updateOnce = true
        response = { Response(body = "<html>Not a subscription</html>", onBodyRead = {
            if (updateOnce) {
                updateOnce = false
                runBlocking {
                    repository.renameSubscription(source.id, "Concurrent user rename")
                    repository.setLastSelectedProfile(source.id, children.last().id)
                    concurrent = database.subscriptionSourceDao().findById(source.id)!!
                }
            }
        }) }
        try {
            repository.importValidatedSubscription(source.sourceUrl, null)
            fail("HTML must not validate")
        } catch (error: SubscriptionImportException) {
            assertEquals(SubscriptionImportFailure.VALIDATION_FAILED, error.reason)
        }
        assertEquals(concurrent, database.subscriptionSourceDao().findById(source.id))
        assertEquals(children, database.profileDao().findBySubscriptionId(source.id))
    }

    @Test fun cancelledValidatedNewImportRemovesOnlyItsEmptyProvisionalSource() = runBlocking {
        val repository = repository()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        response = { Response(body = realBody(), bodyEntered = entered, bodyRelease = release) }
        val job = launch(Dispatchers.Default) { repository.importValidatedSubscription("https://example.invalid/sub/new", "Synthetic") }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            job.cancel()
        } finally { release.countDown() }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(database.subscriptionSourceDao().observeAll().first().isEmpty())
        assertTrue(database.profileDao().observeAll().first().isEmpty())
    }

    @Test fun cancelledValidatedExistingImportPreservesProfilesSelectionAndPolicy() = runBlocking {
        val repository = repository()
        val source = repository.importValidatedSubscription("https://example.invalid/sub/demo", "Synthetic")
        val children = database.profileDao().findBySubscriptionId(source.id)
        repository.setLastSelectedProfile(source.id, children.last().id)
        installPolicy(repository, source.id, SubscriptionClientPolicy(`protected` = true, publicLabel = "Public"))
        val before = database.subscriptionSourceDao().findById(source.id)!!
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        response = { Response(body = markerBody, bodyEntered = entered, bodyRelease = release) }
        val job = launch(Dispatchers.Default) { repository.importValidatedSubscription(source.sourceUrl, null) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            job.cancel()
        } finally { release.countDown() }
        job.join()
        assertEquals(before, database.subscriptionSourceDao().findById(source.id))
        assertEquals(children, database.profileDao().findBySubscriptionId(source.id))
    }

    @Test fun knownTechnicalOnlyImportIsOneOrdinaryDisabledRowWithExactTitle() = runBlocking {
        val repository = repository()
        val title = "Пауза — вернёмся утром. Приложение можно оставить открытым."
        val raw = markerBody.substringBefore('#') + "#" + java.net.URLEncoder.encode(title, StandardCharsets.UTF_8.name()).replace("+", "%20")
        response = { Response(body = raw) }
        val source = repository.importValidatedSubscription("https://example.invalid/sub/technical", "Synthetic")
        val rows = database.profileDao().findBySubscriptionId(source.id)
        assertEquals(SubscriptionSyncStatus.SUCCESS, source.syncStatus)
        assertEquals(1, rows.size)
        assertEquals(title, rows.single().displayName)
        assertEquals(raw, rows.single().sourceRaw)
        assertFalse(rows.single().toDomain().isConnectable)
        assertNull(source.lastSelectedProfileId)
        assertNull(SubscriptionMetadataCodec.decode(source.metadata).clientPolicy)
    }

    @Test fun cancellationImmediatelyAfterProvisionalInsertStillCleansKnownNewID() = runBlocking {
        val delegate = database.subscriptionSourceDao()
        var throwOnce = true
        val cancellingDao = object : SubscriptionSourceDao by delegate {
            override suspend fun upsert(entity: SubscriptionSourceEntity) {
                delegate.upsert(entity)
                if (throwOnce) { throwOnce = false; throw CancellationException("Synthetic cancellation after insert") }
            }
        }
        val repository = repository(sourceDao = cancellingDao)
        try {
            repository.importValidatedSubscription("https://example.invalid/sub/new", "Synthetic")
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertTrue(delegate.observeAll().first().isEmpty())
        assertTrue(database.profileDao().observeAll().first().isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test fun cancellationAfterAcceptedTechnicalCommitNeverDeletesTheOrdinaryRow() = runBlocking {
        val delegate = database.subscriptionSourceDao()
        var throwOnce = true
        val cancellingDao = object : SubscriptionSourceDao by delegate {
            override suspend fun findById(subscriptionId: String): SubscriptionSourceEntity? {
                val current = delegate.findById(subscriptionId)
                if (throwOnce && current?.syncStatus == SubscriptionSyncStatus.SUCCESS.name && current.profileCount == 1) {
                    throwOnce = false
                    throw CancellationException("Synthetic cancellation after accepted commit")
                }
                return current
            }
        }
        val repository = repository(sourceDao = cancellingDao)
        response = { Response(body = markerBody) }
        try {
            repository.importValidatedSubscription("https://example.invalid/sub/technical", "Synthetic")
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        val source = delegate.observeAll().first().single()
        val rows = database.profileDao().findBySubscriptionId(source.id)
        assertEquals(1, rows.size)
        assertEquals(markerBody, rows.single().sourceRaw)
        assertFalse(rows.single().toDomain().isConnectable)
    }

    @Test fun disabledExistingValidatedImportNeverCreatesDuplicateOrFetches() = runBlocking {
        val repository = repository()
        val source = repository.addSubscription("https://example.invalid/sub/disabled", "Synthetic")
        repository.setEnabled(source.id, false)
        val before = database.subscriptionSourceDao().findById(source.id)
        try {
            repository.importValidatedSubscription(source.sourceUrl, "Different")
            fail("Disabled source must be preserved")
        } catch (error: SubscriptionImportException) { assertEquals(SubscriptionImportFailure.DISABLED, error.reason) }
        assertEquals(before, database.subscriptionSourceDao().findById(source.id))
        assertEquals(1, database.subscriptionSourceDao().observeAll().first().size)
        assertTrue(requests.isEmpty())
    }

    @Test fun forcedRefreshBypassesCachedTechnicalETagWhileAutomaticRefreshCanUse304() = runBlocking {
        val repository = repository()
        response = { Response(body = realBody(), headers = mapOf("ETag" to "cached-technical", "Last-Modified" to "Tue, 17 Nov 2026 00:00:00 GMT")) }
        val source = repository.addSubscription("https://example.invalid/sub/demo", "Synthetic")
        repository.refreshSubscription(source.id, true)
        requests.clear()
        response = { request ->
            if (request.headers["if-none-match"] == "cached-technical") Response(status = 304)
            else Response(body = realBody(), headers = mapOf("ETag" to "fresh-real"))
        }
        assertEquals(SubscriptionSyncStatus.SUCCESS, repository.refreshSubscription(source.id, true).status)
        assertEquals(1, requests.size)
        assertNull(requests.single().headers["if-none-match"])
        assertNull(requests.single().headers["if-modified-since"])
        val fresh = repository.getSubscription(source.id)!!
        assertEquals("fresh-real", fresh.etag)
        assertEquals(200, SubscriptionMetadataCodec.decode(fresh.metadata).diagnostics?.selectedStatus)
        database.subscriptionSourceDao().upsert(fresh.copy(lastSuccessAtMs = 0, lastUpdatedAtMs = 0).toEntity())
        requests.clear()
        response = { Response(status = 304) }
        repository.refreshSubscription(source.id, false)
        assertEquals("fresh-real", requests.single().headers["if-none-match"])
        assertEquals("Tue, 17 Nov 2026 00:00:00 GMT", requests.single().headers["if-modified-since"])
    }

    @Test fun headerlessMarkersUseSameIdentityAndKeepWinningRealCandidate() = runBlocking {
        val repository = repository()
        val source = repository.addSubscription("https://example.invalid/sub/demo", "Synthetic")
        repository.refreshSubscription(source.id, true)
        val before = database.profileDao().findBySubscriptionId(source.id)
        repository.setLastSelectedProfile(source.id, before.last().id)
        requests.clear()
        var attempt = 0
        response = {
            when (attempt++) {
                0 -> Response(body = unknownMarkerBody, headers = mapOf("ETag" to "bad-marker"))
                1 -> Response(body = realBody(), headers = mapOf("ETag" to "accepted-real"))
                2 -> Response(failure = SocketTimeoutException("synthetic timeout"))
                else -> Response(body = "{malformed synthetic payload}")
            }
        }

        val result = repository.refreshSubscription(source.id, true)
        val saved = repository.getSubscription(source.id)!!
        val profiles = database.profileDao().findBySubscriptionId(source.id)

        assertEquals(SubscriptionSyncStatus.SUCCESS, result.status)
        assertEquals(before.map { it.id }, profiles.map { it.id })
        assertEquals(before.map { it.sourceRaw }, profiles.map { it.sourceRaw })
        assertEquals(before.map { it.sourceOrder }, profiles.map { it.sourceOrder })
        assertEquals(before.last().id, saved.lastSelectedProfileId)
        assertEquals("accepted-real", saved.etag)
        assertTrue(requests.size >= 3)
        assertEquals(setOf("synthetic-existing-device"), requests.mapNotNull { it.headers["x-hwid"] }.toSet())
        assertNull(SubscriptionMetadataCodec.decode(saved.metadata).providerMessage)
    }

    @Test fun markerOnlyResponseRetainsLastGoodChildrenSelectionValidatorsAndPolicy() = runBlocking {
        val repository = repository()
        response = { Response(body = realBody(), headers = mapOf("ETag" to "last-good")) }
        val source = repository.addSubscription("https://example.invalid/sub/demo", "Synthetic")
        repository.refreshSubscription(source.id, true)
        val children = database.profileDao().findBySubscriptionId(source.id)
        repository.setLastSelectedProfile(source.id, children.last().id)
        val oldSource = repository.getSubscription(source.id)!!
        val policy = SubscriptionClientPolicy(`protected` = true, publicLabel = "Public label", revision = 5)
        database.subscriptionSourceDao().upsert(oldSource.copy(metadata = SubscriptionMetadataCodec.encode(
            SubscriptionMetadataCodec.decode(oldSource.metadata).copy(clientPolicy = policy)
        )).toEntity())
        response = { Response(body = unknownMarkerBody, headers = mapOf("ETag" to "unusable-marker")) }

        val result = repository.refreshSubscription(source.id, true)
        val saved = repository.getSubscription(source.id)!!

        assertEquals(SubscriptionSyncStatus.EMPTY, result.status)
        assertEquals(children, database.profileDao().findBySubscriptionId(source.id))
        assertEquals(children.last().id, saved.lastSelectedProfileId)
        assertEquals("last-good", saved.etag)
        assertEquals(oldSource.lastSuccessAtMs, saved.lastSuccessAtMs)
        assertEquals(policy, SubscriptionMetadataCodec.decode(saved.metadata).clientPolicy)
        assertEquals("Работы до 18:00 — 100%", SubscriptionMetadataCodec.decode(saved.metadata).providerMessage)
        assertEquals(2, result.importedProfilesCount)
    }

    @Test fun failedAlternativeClientsCannotDiscardUsableInitialResponse() = runBlocking {
        val repository = repository()
        var attempt = 0
        response = {
            when (attempt++) {
                0 -> Response(body = realBody(realityWithoutShortId = true))
                1 -> Response(failure = SocketTimeoutException("synthetic timeout"))
                2 -> Response(body = "{malformed synthetic payload}")
                else -> Response(body = unknownMarkerBody)
            }
        }
        val source = repository.addSubscription("https://example.invalid/sub/demo", "Synthetic")

        val result = repository.refreshSubscription(source.id, true)

        assertEquals(SubscriptionSyncStatus.SUCCESS, result.status)
        assertEquals(2, database.profileDao().findBySubscriptionId(source.id).size)
        assertEquals(0, SubscriptionMetadataCodec.decode(repository.getSubscription(source.id)!!.metadata).diagnostics?.markerEntries)
    }

    @Test fun cancellationRestoresPreviousStateWithoutReplacingChildren() = runBlocking {
        val repository = repository()
        val source = repository.addSubscription("https://example.invalid/sub/demo", "Synthetic")
        repository.refreshSubscription(source.id, true)
        val before = repository.getSubscription(source.id)!!
        val children = database.profileDao().findBySubscriptionId(source.id)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        response = { Response(body = markerBody, bodyEntered = entered, bodyRelease = release) }
        val job = launch(Dispatchers.Default) { repository.refreshSubscription(source.id, true) }
        try {
            assertTrue("scripted body must be reached", entered.await(5, TimeUnit.SECONDS))
            job.cancel()
        } finally {
            release.countDown()
        }
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(before, repository.getSubscription(source.id))
        assertEquals(children, database.profileDao().findBySubscriptionId(source.id))
    }

    @Test fun validFalseWithMalformedOrTruncated200CannotChangePolicyOrWatermarks() = runBlocking {
        val repository = repository()
        val source = repository.addSubscription("https://example.invalid/sub/demo", "Synthetic")
        repository.refreshSubscription(source.id, true)
        val now = System.currentTimeMillis() / 1000
        val old = SubscriptionClientPolicyReader.read(listOf(metadata(now, 2, true)), source.sourceUrl, source.sourceUrl, 200, now * 1000)!!
        installPolicy(repository, source.id, old)
        val before = repository.getSubscription(source.id)!!
        val children = database.profileDao().findBySubscriptionId(source.id)
        val clear = metadata(now + 1, 3, false)
        response = { Response(body = "{malformed synthetic payload}", headers = mapOf("X-Nora-Metadata" to clear)) }
        assertEquals(SubscriptionSyncStatus.ERROR, repository.refreshSubscription(source.id, true).status)
        assertEquals(old, SubscriptionMetadataCodec.decode(repository.getSubscription(source.id)!!.metadata).clientPolicy)
        assertEquals(children, database.profileDao().findBySubscriptionId(source.id))
        response = { Response(body = realBody(), headers = mapOf("X-Nora-Metadata" to clear, "Content-Length" to "999999")) }
        assertEquals(SubscriptionSyncStatus.ERROR, repository.refreshSubscription(source.id, true).status)
        val saved = repository.getSubscription(source.id)!!
        assertEquals(old, SubscriptionMetadataCodec.decode(saved.metadata).clientPolicy)
        assertEquals(before.lastSuccessAtMs, saved.lastSuccessAtMs)
        assertEquals(before.lastSelectedProfileId, saved.lastSelectedProfileId)
        assertEquals(children, database.profileDao().findBySubscriptionId(source.id))
    }

    @Test fun technicalOnlyUpdateKeepsOldRealIDsRawSelectionAndRelativeOrder() = runBlocking {
        val repository = repository()
        val source = repository.importValidatedSubscription("https://example.invalid/sub/demo", "Synthetic")
        val children = database.profileDao().findBySubscriptionId(source.id)
        repository.setLastSelectedProfile(source.id, children.last().id)
        response = { Response(body = markerBody) }
        val result = repository.refreshSubscription(source.id, true)
        val saved = repository.getSubscription(source.id)!!
        val rows = database.profileDao().findBySubscriptionId(source.id)
        assertEquals(SubscriptionSyncStatus.SUCCESS, result.status)
        assertEquals(SubscriptionContentAcceptance.TECHNICAL_ENTRIES, result.acceptance)
        assertEquals(3, rows.size)
        assertEquals(markerBody, rows.first().sourceRaw)
        assertFalse(rows.first().toDomain().isConnectable)
        assertEquals(children.map { it.id }, rows.drop(1).map { it.id })
        assertEquals(children.map { it.sourceRaw }, rows.drop(1).map { it.sourceRaw })
        assertTrue(rows.drop(1).all { it.toDomain().isConnectable })
        assertEquals(children.last().id, saved.lastSelectedProfileId)
        response = { Response(body = realBody()) }
        repository.refreshSubscription(source.id, true)
        assertEquals(children.map { it.id }, database.profileDao().findBySubscriptionId(source.id).map { it.id })
        assertEquals(children.last().id, repository.getSubscription(source.id)!!.lastSelectedProfileId)
    }

    @Test fun empty204CannotAdoptDisplayPolicyOrReplaceLastGood() = runBlocking {
        val repository = repository()
        val source = repository.importValidatedSubscription("https://example.invalid/sub/demo", "Synthetic")
        val now = System.currentTimeMillis() / 1000
        val old = SubscriptionClientPolicyReader.read(listOf(metadata(now - 100, 2, true)), source.sourceUrl, source.sourceUrl, 200, now * 1000)!!
        installPolicy(repository, source.id, old)
        val children = database.profileDao().findBySubscriptionId(source.id)
        for (fixture in listOf(Response(status = 204), Response(status = 204, headers = mapOf("X-Nora-Metadata" to metadata(now, 3, false))))) {
            response = { fixture }
            assertEquals(SubscriptionSyncStatus.ERROR, repository.refreshSubscription(source.id, true).status)
            assertEquals(old, SubscriptionMetadataCodec.decode(repository.getSubscription(source.id)!!.metadata).clientPolicy)
            assertEquals(children, database.profileDao().findBySubscriptionId(source.id))
        }
    }

    @Test fun metadataOn304AndUntrustedRedirectNeverUnlocksOrExtendsTTL() = runBlocking {
        val repository = repository()
        val source = repository.addSubscription("https://example.invalid/sub/demo", "Synthetic")
        repository.refreshSubscription(source.id, true)
        val now = System.currentTimeMillis() / 1000
        val old = SubscriptionClientPolicyReader.read(listOf(metadata(now - 1, 2, true)), source.sourceUrl, source.sourceUrl, 200, now * 1000)!!
        installPolicy(repository, source.id, old)
        val clear = metadata(now, 3, false)
        response = { Response(status = 304, headers = mapOf("X-Nora-Metadata" to clear)) }
        repository.refreshSubscription(source.id, true)
        assertEquals(old, SubscriptionMetadataCodec.decode(repository.getSubscription(source.id)!!.metadata).clientPolicy)
        requests.clear()
        response = { request ->
            if (request.url.startsWith("https://example.invalid/")) Response(status = 302, headers = mapOf("Location" to "https://untrusted.invalid/sub/redirect"))
            else Response(body = realBody(), headers = mapOf("X-Nora-Metadata" to clear))
        }
        repository.refreshSubscription(source.id, true)
        assertEquals(old, SubscriptionMetadataCodec.decode(repository.getSubscription(source.id)!!.metadata).clientPolicy)
        val redirected = requests.filter { it.url.startsWith("https://untrusted.invalid/") }
        assertTrue(redirected.isNotEmpty())
        assertTrue(redirected.all { "x-hwid" !in it.headers && "x-nora-client" !in it.headers })
    }

    @Test fun HTTPerrorsAndDuplicateMetadataHeadersCannotUnprotectOtherSubscriptions() = runBlocking {
        val repository = repository()
        val first = repository.addSubscription("https://example.invalid/sub/first", "First")
        repository.refreshSubscription(first.id, true)
        val now = System.currentTimeMillis() / 1000
        val old = SubscriptionClientPolicyReader.read(listOf(metadata(now - 1, 2, true)), first.sourceUrl, first.sourceUrl, 200, now * 1000)!!
        installPolicy(repository, first.id, old)
        val second = repository.addSubscription("https://example.invalid/sub/second", "Second")
        response = { Response(body = realBody(), headers = mapOf("X-Nora-Metadata" to metadata(now, 3, false))) }
        repository.refreshSubscription(second.id, true)
        assertEquals(old, SubscriptionMetadataCodec.decode(repository.getSubscription(first.id)!!.metadata).clientPolicy)
        for (status in listOf(403, 503)) {
            response = { Response(status = status, headers = mapOf("X-Nora-Metadata" to metadata(now, 3, false))) }
            assertEquals(SubscriptionSyncStatus.ERROR, repository.refreshSubscription(first.id, true).status)
            assertEquals(old, SubscriptionMetadataCodec.decode(repository.getSubscription(first.id)!!.metadata).clientPolicy)
        }
        response = { Response(body = realBody(), headers = mapOf("X-Nora-Metadata" to metadata(now, 3, false), "x-nora-metadata" to metadata(now, 3, false))) }
        repository.refreshSubscription(first.id, true)
        assertEquals(old, SubscriptionMetadataCodec.decode(repository.getSubscription(first.id)!!.metadata).clientPolicy)
    }

    @Test fun technicalBodyWithoutEnvelopePreservesAcceptedDisplayPolicyVerbatim() = runBlocking {
        val repository = repository()
        val source = repository.importValidatedSubscription("https://example.invalid/sub/demo", "Synthetic")
        val now = System.currentTimeMillis() / 1000
        val accepted = SubscriptionClientPolicyReader.read(listOf(metadata(now - 100, 4, true)), source.sourceUrl, source.sourceUrl, 200, now * 1000)!!
        installPolicy(repository, source.id, accepted)
        response = { Response(body = markerBody) }
        val returned = repository.importValidatedSubscription(source.sourceUrl, null)
        assertEquals(SubscriptionSyncStatus.SUCCESS, returned.syncStatus)
        assertEquals(accepted, SubscriptionMetadataCodec.decode(returned.metadata).clientPolicy)
        assertEquals(3, database.profileDao().findBySubscriptionId(source.id).size)
    }

    @Test fun mixedTechnicalAndRealEntriesKeepExactProviderOrderWithoutPickingTechnical() = runBlocking {
        val repository = repository()
        val real = org.json.JSONArray(realBody())
        val body = org.json.JSONArray().put(markerBody).put(real.getJSONObject(1)).put(real.getJSONObject(0)).toString()
        response = { Response(body = body) }
        val source = repository.importValidatedSubscription("https://example.invalid/sub/mixed", "Synthetic")
        val rows = database.profileDao().findBySubscriptionId(source.id)
        assertEquals(listOf(markerBody, real.getJSONObject(1).toString(), real.getJSONObject(0).toString()), rows.map { it.sourceRaw })
        assertEquals(listOf(0, 1, 2), rows.map { it.sourceOrder })
        assertEquals(1, rows.count { !it.toDomain().isConnectable })
        assertEquals(rows[1].id, source.lastSelectedProfileId)
        assertNull(SubscriptionMetadataCodec.decode(source.metadata).clientPolicy)
    }

    @Test fun validTechnicalDisplayBodyCanAtomicallyAdoptExplicitProtectionClear() = runBlocking {
        val repository = repository()
        val source = repository.importValidatedSubscription("https://example.invalid/sub/demo", "Synthetic")
        val now = System.currentTimeMillis() / 1000
        val old = SubscriptionClientPolicyReader.read(listOf(metadata(now - 100, 2, true)), source.sourceUrl, source.sourceUrl, 200, now * 1000)!!
        installPolicy(repository, source.id, old)
        val children = database.profileDao().findBySubscriptionId(source.id)
        response = { Response(body = markerBody, headers = mapOf("X-Nora-Metadata" to metadata(now, 3, false))) }
        val updated = repository.importValidatedSubscription(source.sourceUrl, null)
        assertFalse(SubscriptionMetadataCodec.decode(updated.metadata).clientPolicy!!.endpointProtectionEnabled)
        assertEquals(3, database.profileDao().findBySubscriptionId(source.id).size)
        assertEquals(children.map { it.id }, database.profileDao().findBySubscriptionId(source.id).drop(1).map { it.id })
    }

    @Test fun unknownMarkerFailureNeverDeletesLastGoodKnownTechnicalRow() = runBlocking {
        val repository = repository()
        response = { Response(body = markerBody) }
        val source = repository.importValidatedSubscription("https://example.invalid/sub/known-cache", "Synthetic")
        val before = repository.getSubscription(source.id)!!
        val rows = database.profileDao().findBySubscriptionId(source.id)
        assertEquals(1, rows.size)
        assertFalse(rows.single().toDomain().isConnectable)
        response = { Response(body = unknownMarkerBody) }
        val failed = repository.refreshSubscription(source.id, true)
        val after = repository.getSubscription(source.id)!!
        assertEquals(SubscriptionContentAcceptance.NONE, failed.acceptance)
        assertEquals(1, after.profileCount)
        assertEquals(before.childProfileIds, after.childProfileIds)
        assertEquals(before.lastSelectedProfileId, after.lastSelectedProfileId)
        assertEquals(before.lastSuccessAtMs, after.lastSuccessAtMs)
        assertEquals(rows, database.profileDao().findBySubscriptionId(source.id))
        val beforeStrict = database.subscriptionSourceDao().findById(source.id)
        try { repository.importValidatedSubscription(source.sourceUrl, null); fail("Unknown markers must not validate") }
        catch (error: SubscriptionImportException) { assertEquals(SubscriptionImportFailure.VALIDATION_FAILED, error.reason) }
        assertEquals(beforeStrict, database.subscriptionSourceDao().findById(source.id))
        assertEquals(rows, database.profileDao().findBySubscriptionId(source.id))
    }

    @Test fun technicalEntryInOneSubscriptionNeverChangesAnotherSubscription() = runBlocking {
        val repository = repository()
        val first = repository.importValidatedSubscription("https://example.invalid/sub/first", "First")
        val children = database.profileDao().findBySubscriptionId(first.id)
        repository.setLastSelectedProfile(first.id, children.last().id)
        val before = database.subscriptionSourceDao().findById(first.id)
        response = { Response(body = markerBody) }
        val other = repository.importValidatedSubscription("https://example.invalid/sub/other", "Other")
        assertEquals(1, other.profileCount)
        assertNull(other.lastSelectedProfileId)
        assertEquals(before, database.subscriptionSourceDao().findById(first.id))
        assertEquals(children, database.profileDao().findBySubscriptionId(first.id))
    }

    @Test fun strictValidatedImportRejectsUnsolicited304WithoutChangingExistingSnapshot() = runBlocking {
        val repository = repository()
        val source = repository.importValidatedSubscription("https://example.invalid/sub/demo", "Synthetic")
        val before = database.subscriptionSourceDao().findById(source.id)
        val children = database.profileDao().findBySubscriptionId(source.id)
        response = { Response(status = 304) }
        try { repository.importValidatedSubscription(source.sourceUrl, null); fail("Fresh body required") }
        catch (error: SubscriptionImportException) { assertEquals(SubscriptionImportFailure.VALIDATION_FAILED, error.reason) }
        assertEquals(before, database.subscriptionSourceDao().findById(source.id))
        assertEquals(children, database.profileDao().findBySubscriptionId(source.id))
    }

    @Test fun displayOnlyRefreshKeepsIDsProviderOrderSelectionAndIncomingRaw() = runBlocking {
        val repository = repository()
        val source = repository.addSubscription("https://example.invalid/sub/demo", "Synthetic")
        repository.refreshSubscription(source.id, true)
        val children = database.profileDao().findBySubscriptionId(source.id)
        repository.setLastSelectedProfile(source.id, children.last().id)
        val renamed = org.json.JSONArray(realBody())
        for (index in 0 until renamed.length()) {
            renamed.getJSONObject(index).put("remarks", "Renamed $index")
                .put("meta", JSONObject().put("serverDescription", "Synthetic count ${index + 100}"))
        }
        response = { Response(body = renamed.toString()) }
        repository.refreshSubscription(source.id, true)
        val saved = database.profileDao().findBySubscriptionId(source.id)
        assertEquals(children.map { it.id }, saved.map { it.id })
        assertEquals(children.map { it.sourceOrder }, saved.map { it.sourceOrder })
        assertEquals(children.last().id, repository.getSubscription(source.id)!!.lastSelectedProfileId)
        for (index in saved.indices) {
            assertEquals(renamed.getJSONObject(index).toString(), saved[index].sourceRaw)
            assertTrue(saved[index].normalizedJson!!.contains("Renamed $index"))
        }
    }

    @Test fun DAOInsertFailureRollsBackTransportAndRetainsCommittedProtectionWatermarks() = runBlocking {
        val delegate = database.profileDao()
        var failNextInsert = false
        val failingDao = object : ProfileDao by delegate {
            override suspend fun insertAll(entities: List<ProfileEntity>) {
                if (failNextInsert) { failNextInsert = false; error("Synthetic DAO failure") }
                delegate.insertAll(entities)
            }
        }
        val repository = repository(failingDao)
        val source = repository.addSubscription("https://example.invalid/sub/demo", "Synthetic")
        repository.refreshSubscription(source.id, true)
        val now = System.currentTimeMillis() / 1000
        val committed = SubscriptionClientPolicyReader.read(listOf(metadata(now - 100, 2, true)), source.sourceUrl, source.sourceUrl, 200, now * 1000)!!
        installPolicy(repository, source.id, committed)
        val children = database.profileDao().findBySubscriptionId(source.id)
        val before = repository.getSubscription(source.id)!!
        response = { Response(body = realBody().replace("Synthetic ", "Changed "), headers = mapOf("X-Nora-Metadata" to metadata(now, 3, false))) }
        failNextInsert = true
        assertEquals(SubscriptionSyncStatus.ERROR, repository.refreshSubscription(source.id, true).status)
        val after = repository.getSubscription(source.id)!!
        assertEquals(committed, SubscriptionMetadataCodec.decode(after.metadata).clientPolicy)
        assertEquals(before.lastSuccessAtMs, after.lastSuccessAtMs)
        assertEquals(before.lastSelectedProfileId, after.lastSelectedProfileId)
        assertEquals(children, database.profileDao().findBySubscriptionId(source.id))
    }

    @Test fun inferiorRetryCannotPairItsClearPolicyWithDifferentWinningBody() = runBlocking {
        val repository = repository()
        val source = repository.addSubscription("https://example.invalid/sub/demo", "Synthetic")
        repository.refreshSubscription(source.id, true)
        val now = System.currentTimeMillis() / 1000
        val committed = SubscriptionClientPolicyReader.read(listOf(metadata(now - 100, 2, true)), source.sourceUrl, source.sourceUrl, 200, now * 1000)!!
        installPolicy(repository, source.id, committed)
        val initial = realBody(true).replace("\"realitySettings\":{}", "\"realitySettings\":{\"publicKey\":\"synthetic-public-key\"}")
        val oneServer = org.json.JSONArray(realBody()).getJSONObject(0).toString()
        var attempt = 0
        response = {
            when (attempt++) {
                0 -> Response(body = initial)
                1 -> Response(body = oneServer, headers = mapOf("X-Nora-Metadata" to metadata(now, 3, false)))
                else -> Response(body = unknownMarkerBody)
            }
        }
        assertEquals(SubscriptionSyncStatus.SUCCESS, repository.refreshSubscription(source.id, true).status)
        assertEquals(committed, SubscriptionMetadataCodec.decode(repository.getSubscription(source.id)!!.metadata).clientPolicy)
        val children = database.profileDao().findBySubscriptionId(source.id)
        assertEquals(2, children.size)
        assertTrue(children.all { it.sourceRaw.contains("realitySettings") })
    }

    @Test fun presentV1PartialEmptyAndInvalidTransportBodiesKeepEntireLastGoodSnapshot() = runBlocking {
        val repository = repository()
        val source = repository.addSubscription("https://example.invalid/sub/demo", "Synthetic")
        repository.refreshSubscription(source.id, true)
        val now = System.currentTimeMillis() / 1000
        val committed = SubscriptionClientPolicyReader.read(listOf(metadata(now - 100, 2, true)), source.sourceUrl, source.sourceUrl, 200, now * 1000)!!
        installPolicy(repository, source.id, committed)
        val before = repository.getSubscription(source.id)!!
        val children = database.profileDao().findBySubscriptionId(source.id)
        val valid = org.json.JSONArray(realBody()).getJSONObject(0).toString()
        val invalidBodies = listOf("[$valid,\"invalid synthetic entry\"]", "[]", unknownMarkerBody,
            """{"outbounds":[{"protocol":"blackhole","settings":{}}]}""",
            valid.replace("\"port\":443", "\"port\":0"), valid.replace("\"port\":443", "\"port\":65536"),
            """{"remarks":"Synthetic","outbounds":[{"protocol":"trojan","settings":{"servers":[{"address":"192.0.2.1","port":443}]}}]}""")
        for (body in invalidBodies) {
            response = { Response(body = body, headers = mapOf("X-Nora-Metadata" to metadata(now, 3, false))) }
            assertEquals(SubscriptionSyncStatus.ERROR, repository.refreshSubscription(source.id, true).status)
            val after = repository.getSubscription(source.id)!!
            assertEquals(committed, SubscriptionMetadataCodec.decode(after.metadata).clientPolicy)
            assertEquals(before.lastSuccessAtMs, after.lastSuccessAtMs)
            assertEquals(children, database.profileDao().findBySubscriptionId(source.id))
        }
    }

    @Test fun metadataExpiringDuringBodyReadCannotAdvanceProtectionOrReplayState() = runBlocking {
        val fakeNow = AtomicLong(System.currentTimeMillis() / 1000 * 1000)
        val repository = repository(clock = fakeNow::get)
        val source = repository.addSubscription("https://example.invalid/sub/demo", "Synthetic")
        repository.refreshSubscription(source.id, true)
        val seconds = fakeNow.get() / 1000
        val committed = SubscriptionClientPolicyReader.read(listOf(metadata(seconds - 100, 2, true)), source.sourceUrl, source.sourceUrl, 200, fakeNow.get())!!
        installPolicy(repository, source.id, committed)
        val children = database.profileDao().findBySubscriptionId(source.id)
        response = { Response(body = realBody(), headers = mapOf("X-Nora-Metadata" to metadata(seconds - 25, 3, false, ttlSeconds = 30)), onBodyRead = { fakeNow.addAndGet(6000) }) }
        assertEquals(SubscriptionSyncStatus.ERROR, repository.refreshSubscription(source.id, true).status)
        assertEquals(committed, SubscriptionMetadataCodec.decode(repository.getSubscription(source.id)!!.metadata).clientPolicy)
        assertEquals(children, database.profileDao().findBySubscriptionId(source.id))
    }

    @Test fun metadataExpiringInsideTransactionRollsBackChildrenAndPolicyTogether() = runBlocking {
        val fakeNow = AtomicLong(System.currentTimeMillis() / 1000 * 1000)
        val delegate = database.profileDao()
        var expireAfterInsert = false
        val expiringDao = object : ProfileDao by delegate {
            override suspend fun insertAll(entities: List<ProfileEntity>) {
                delegate.insertAll(entities)
                if (expireAfterInsert) { expireAfterInsert = false; fakeNow.addAndGet(6000) }
            }
        }
        val repository = repository(expiringDao, fakeNow::get)
        val source = repository.addSubscription("https://example.invalid/sub/demo", "Synthetic")
        repository.refreshSubscription(source.id, true)
        val seconds = fakeNow.get() / 1000
        val committed = SubscriptionClientPolicyReader.read(listOf(metadata(seconds - 100, 2, true)), source.sourceUrl, source.sourceUrl, 200, fakeNow.get())!!
        installPolicy(repository, source.id, committed)
        val children = database.profileDao().findBySubscriptionId(source.id)
        val before = repository.getSubscription(source.id)!!
        response = { Response(body = realBody().replace("Synthetic ", "Changed "), headers = mapOf("X-Nora-Metadata" to metadata(seconds - 25, 3, false, ttlSeconds = 30))) }
        expireAfterInsert = true
        assertEquals(SubscriptionSyncStatus.ERROR, repository.refreshSubscription(source.id, true).status)
        val after = repository.getSubscription(source.id)!!
        assertEquals(committed, SubscriptionMetadataCodec.decode(after.metadata).clientPolicy)
        assertEquals(before.lastSelectedProfileId, after.lastSelectedProfileId)
        assertEquals(children, database.profileDao().findBySubscriptionId(source.id))
    }

    @Test fun returnToOriginRedirectChainCannotAuthorizeClear() = runBlocking {
        val repository = repository()
        val source = repository.addSubscription("https://example.invalid/sub/demo", "Synthetic")
        repository.refreshSubscription(source.id, true)
        val now = System.currentTimeMillis() / 1000
        val committed = SubscriptionClientPolicyReader.read(listOf(metadata(now - 100, 2, true)), source.sourceUrl, source.sourceUrl, 200, now * 1000)!!
        installPolicy(repository, source.id, committed)
        requests.clear()
        response = { request -> when {
            request.url.endsWith("/sub/demo") -> Response(status = 302, headers = mapOf("Location" to "https://untrusted.invalid/hop"))
            request.url.startsWith("https://untrusted.invalid/") -> Response(status = 302, headers = mapOf("Location" to "https://example.invalid/sub/returned"))
            else -> Response(body = realBody(), headers = mapOf("X-Nora-Metadata" to metadata(now, 3, false)))
        } }
        repository.refreshSubscription(source.id, true)
        assertEquals(committed, SubscriptionMetadataCodec.decode(repository.getSubscription(source.id)!!.metadata).clientPolicy)
        val returned = requests.last()
        assertTrue(returned.url.endsWith("/sub/returned"))
        assertNull(returned.headers["x-nora-client"])
        assertNull(returned.headers["x-hwid"])
    }

    private suspend fun installPolicy(repository: RoomSubscriptionRepository, id: String, policy: SubscriptionClientPolicy) {
        val source = repository.getSubscription(id)!!
        database.subscriptionSourceDao().upsert(source.copy(metadata = SubscriptionMetadataCodec.encode(
            SubscriptionMetadataCodec.decode(source.metadata).copy(clientPolicy = policy)
        )).toEntity())
    }

    private fun metadata(issuedAt: Long, revision: Long, `protected`: Boolean, ttlSeconds: Int = 300): String {
        val value = JSONObject().put("v", 1).put("mode", "full").put("revision", revision).put("issued_at", issuedAt).put("ttl_seconds", ttlSeconds)
            .put("display", JSONObject().put("protected", `protected`).put("public_label", if (`protected`) "Public label" else JSONObject.NULL))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toString().toByteArray(StandardCharsets.UTF_8))
    }

    private data class Request(val url: String, val headers: Map<String, String>)
    private data class Response(
        val status: Int = 200,
        val body: String = "",
        val headers: Map<String, String> = emptyMap(),
        val failure: Throwable? = null,
        val bodyEntered: CountDownLatch? = null,
        val bodyRelease: CountDownLatch? = null,
        val onBodyRead: (() -> Unit)? = null
    )

    private class ScriptedConnection(url: URL, private val handler: (Request) -> Response) : HttpURLConnection(url) {
        private val fixture: Response by lazy {
            handler(Request(url.toString(), requestProperties.entries.associate { (key, value) -> key.lowercase() to value.single() }))
        }
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int {
            fixture.failure?.let { throw it }
            return fixture.status
        }
        override fun getHeaderFields(): Map<String, List<String>> = fixture.headers.mapValues { listOf(it.value) }
        override fun getHeaderField(name: String): String? = fixture.headers.entries.firstOrNull { it.key.equals(name, true) }?.value
        override fun getInputStream(): InputStream {
            fixture.bodyEntered?.countDown()
            check(fixture.bodyRelease?.await(5, TimeUnit.SECONDS) != false) { "Synthetic fixture body timeout" }
            fixture.onBodyRead?.invoke()
            return ByteArrayInputStream(fixture.body.toByteArray(StandardCharsets.UTF_8))
        }
        override fun getErrorStream(): InputStream = ByteArrayInputStream(fixture.body.toByteArray(StandardCharsets.UTF_8))
    }

    companion object {
        private const val syntheticLegacyCredential = "00000000-0000-0000-0000-000000000000"
        private val syntheticLegacyHash = SubscriptionServiceRecords.reservedTupleSha256("localhost", syntheticLegacyCredential, 1)
        private val markerBody = "vless://00000000-0000-0000-0000-000000000000@localhost:1?encryption=none&type=tcp&security=none#%D0%A0%D0%B0%D0%B1%D0%BE%D1%82%D1%8B%20%D0%B4%D0%BE%2018%3A00%20%E2%80%94%20100%25"
        private val unknownMarkerBody = markerBody.replace(syntheticLegacyCredential, "11111111-1111-4111-8111-111111111111")
        private fun realBody(realityWithoutShortId: Boolean = false): String {
            val stream = if (realityWithoutShortId) ",\"streamSettings\":{\"security\":\"reality\",\"realitySettings\":{}}" else ""
            return (1..2).joinToString(prefix = "[", postfix = "]") { index ->
                """{"remarks":"Synthetic $index","inbounds":[],"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"192.0.2.$index","port":443,"users":[{"id":"00000000-0000-4000-8000-00000000000$index"}]}]}$stream}],"dns":{"servers":["1.1.1.1"]}}"""
            }
        }
    }
}
