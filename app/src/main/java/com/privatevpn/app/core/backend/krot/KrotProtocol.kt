package com.privatevpn.app.core.backend.krot

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class KrotTransportSession(
    private val rawSocket: Socket,
    private val tlsSocket: SSLSocket,
    val secureStream: KrotSecureStream
) : Closeable {
    override fun close() {
        runCatching { secureStream.close() }
        runCatching { tlsSocket.close() }
        runCatching { rawSocket.close() }
    }
}

object KrotProtocol {
    private val secureRandom = SecureRandom()

    fun open(rawSocket: Socket, spec: KrotConnectionSpec, log: (String) -> Unit = {}): KrotTransportSession {
        val tlsName = spec.server.tlsName.ifBlank { spec.server.host }
        val tlsSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(rawSocket, tlsName, spec.server.port, true) as SSLSocket
        tlsSocket.useClientMode = true
        tlsSocket.soTimeout = HANDSHAKE_TIMEOUT_MS
        tlsSocket.sslParameters = tlsSocket.sslParameters.withSni(tlsName)
        tlsSocket.startHandshake()
        log("KRot TLS cover established with SNI $tlsName")

        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        val keyPair = keyPairGenerator.generateKeyPair()
        val clientPublic = keyPair.public.encoded
        val clientNonce = randomBytes(16)
        val hello = buildClientHello(spec, clientNonce, clientPublic)

        val coverPath = "/assets/${randomBytes(8).toHexLower()}.bin"
        val header = buildString {
            append("POST ").append(coverPath).append(" HTTP/1.1\r\n")
            append("Host: ").append(spec.server.coverHost.ifBlank { tlsName }).append("\r\n")
            append("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) ")
            append("AppleWebKit/537.36 Chrome/125.0 Safari/537.36\r\n")
            append("Accept: */*\r\n")
            append("Content-Type: application/octet-stream\r\n")
            append("Content-Length: ").append(hello.size).append("\r\n")
            append("Connection: keep-alive\r\n\r\n")
        }

        val output = tlsSocket.outputStream
        output.write(header.toByteArray(StandardCharsets.US_ASCII))
        output.write(hello)
        output.flush()
        log("KRot hidden bootstrap sent through HTTP cover")

        val response = readHttpResponse(tlsSocket.inputStream)
        require(response.header.startsWith("HTTP/1.1 200") || response.header.startsWith("HTTP/1.0 200")) {
            "KRot cover response was not HTTP 200"
        }
        val parsed = parseServerHello(response.body, spec, clientNonce)

        val serverPublic = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(parsed.publicKey))
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(keyPair.private)
        agreement.doPhase(serverPublic, true)
        val rawShared = agreement.generateSecret()
        val dotNetShared = sha256(rawShared)

        tlsSocket.soTimeout = 0
        val secure = KrotSecureStream.createClient(
            input = tlsSocket.inputStream,
            output = output,
            shared = dotNetShared,
            credentialKey = spec.credentials.credentialKey,
            clientNonce = clientNonce,
            serverNonce = parsed.nonce
        )
        log("KRot hidden auth accepted; encrypted record layer ready")
        return KrotTransportSession(rawSocket = rawSocket, tlsSocket = tlsSocket, secureStream = secure)
    }

    private fun SSLParameters.withSni(host: String): SSLParameters {
        serverNames = listOf(SNIHostName(host))
        endpointIdentificationAlgorithm = "HTTPS"
        return this
    }

    private fun buildClientHello(spec: KrotConnectionSpec, nonce: ByteArray, publicKey: ByteArray): ByteArray {
        val capabilities = buildCapabilityJson(spec).toByteArray(StandardCharsets.UTF_8)
        val prefix = ByteArrayOutputStream().use { ms ->
            ms.write(nonce)
            ms.write(credentialTag(spec, nonce))
            ms.write(u16(publicKey.size))
            ms.write(publicKey)
            ms.write(u16(capabilities.size))
            ms.write(capabilities)
            ms.toByteArray()
        }
        return ByteArrayOutputStream().use { ms ->
            ms.write(prefix)
            ms.write(hmac(spec.credentialKeyBytes(), withLabel("nvp1 client hello", prefix)))
            ms.toByteArray()
        }
    }

    private fun parseServerHello(data: ByteArray, spec: KrotConnectionSpec, clientNonce: ByteArray): ParsedServerHello {
        require(data.size >= 16 + 2 + 2 + 32) { "KRot server hello is too short" }

        var offset = 0
        fun read(len: Int, label: String): ByteArray {
            require(offset + len <= data.size) { "Bad $label length" }
            return data.copyOfRange(offset, offset + len).also { offset += len }
        }
        fun readVariable(maxLength: Int, label: String): ByteArray {
            val lengthBytes = read(2, "$label length")
            val length = u16(lengthBytes, 0)
            require(length <= maxLength) { "$label is too large" }
            return read(length, label)
        }

        val nonce = read(16, "server nonce")
        val publicKey = readVariable(4096, "server public key")
        val capabilities = readVariable(8192, "server capabilities")
        val prefix = data.copyOfRange(0, offset)
        val mac = read(32, "server hello mac")
        val expected = hmac(
            spec.credentialKeyBytes(),
            withLabel("nvp1 server hello", clientNonce, prefix)
        )
        require(MessageDigest.isEqual(expected, mac)) { "Bad KRot server hello MAC" }
        require(offset == data.size) { "KRot server hello has trailing bytes" }
        return ParsedServerHello(nonce = nonce, publicKey = publicKey, capabilities = capabilities)
    }

    private fun credentialTag(spec: KrotConnectionSpec, nonce: ByteArray): ByteArray =
        hmac(
            spec.credentialKeyBytes(),
            withLabel(
                "nvp1 credential tag",
                spec.credentials.credentialId.toByteArray(StandardCharsets.UTF_8),
                nonce
            )
        ).copyOfRange(0, 16)

    private fun buildCapabilityJson(spec: KrotConnectionSpec): String =
        """
        {
          "transport_profile": "${spec.transportProfile}",
          "relay": ["packet_v1"],
          "commands": ["packet", "ping", "pong", "close"],
          "compliance": "NVP-1D"
        }
        """.trimIndent()

    private fun readHttpResponse(input: InputStream): HttpResponse {
        val headerBytes = ArrayList<Byte>(512)
        while (true) {
            val next = input.read()
            if (next < 0) throw EOFException("KRot HTTP cover response ended before headers")
            headerBytes += next.toByte()
            val n = headerBytes.size
            if (
                n >= 4 &&
                headerBytes[n - 4] == '\r'.code.toByte() &&
                headerBytes[n - 3] == '\n'.code.toByte() &&
                headerBytes[n - 2] == '\r'.code.toByte() &&
                headerBytes[n - 1] == '\n'.code.toByte()
            ) {
                break
            }
            require(n <= 8192) { "KRot HTTP cover response header too large" }
        }

        val header = String(headerBytes.toByteArray(), StandardCharsets.US_ASCII)
        val contentLength = header.lineSequence()
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.toIntOrNull()
            ?: throw IllegalStateException("KRot HTTP cover response has no Content-Length")
        require(contentLength in 1..65535) { "KRot HTTP cover response body length is invalid" }
        return HttpResponse(header = header, body = readExact(input, contentLength))
    }

    private fun randomBytes(len: Int): ByteArray = ByteArray(len).also(secureRandom::nextBytes)

    private data class HttpResponse(val header: String, val body: ByteArray)
    private data class ParsedServerHello(
        val nonce: ByteArray,
        val publicKey: ByteArray,
        val capabilities: ByteArray
    )

    private const val HANDSHAKE_TIMEOUT_MS = 15_000
}

enum class KrotFrameType(val code: Int) {
    Packet(1),
    Ping(2),
    Pong(3),
    Close(4);

    companion object {
        fun fromCode(code: Int): KrotFrameType? = entries.firstOrNull { it.code == code }
    }
}

data class KrotFrame(
    val type: KrotFrameType,
    val payload: ByteArray
)

class KrotSecureStream(
    private val input: InputStream,
    private val output: OutputStream,
    private val sendKey: ByteArray,
    private val recvKey: ByteArray
) : Closeable {
    private var sendSeq: Long = 0L
    private var recvSeq: Long = 0L
    private val writeLock = Any()

    fun writeFrame(type: KrotFrameType, payload: ByteArray) {
        require(payload.size <= 65535) { "KRot frame is too large" }

        val plain = ByteArray(1 + 4 + payload.size)
        plain[0] = type.code.toByte()
        writeU32(plain, 1, payload.size.toLong())
        payload.copyInto(plain, destinationOffset = 5)

        synchronized(writeLock) {
            val seq = sendSeq++
            val encrypted = encrypt(sendKey, seq, plain)
            val len = ByteArray(4)
            writeU32(len, 0, encrypted.size.toLong())
            output.write(len)
            output.write(encrypted)
            output.flush()
        }
    }

    fun readFrame(): KrotFrame {
        val lenBuf = readExact(input, 4)
        val len = readU32(lenBuf, 0)
        require(len in 21..65556) { "Bad KRot encrypted record length" }
        val encrypted = readExact(input, len)
        val seq = recvSeq++
        val plain = decrypt(recvKey, seq, encrypted)
        require(plain.size >= 5) { "Bad KRot frame length" }
        val type = KrotFrameType.fromCode(plain[0].toInt() and 0xff)
            ?: throw IllegalStateException("Unknown KRot frame type ${plain[0].toInt() and 0xff}")
        val payloadLen = readU32(plain, 1)
        require(payloadLen == plain.size - 5) { "Bad KRot payload length" }
        return KrotFrame(type = type, payload = plain.copyOfRange(5, plain.size))
    }

    override fun close() {
        runCatching { output.flush() }
    }

    companion object {
        fun createClient(
            input: InputStream,
            output: OutputStream,
            shared: ByteArray,
            credentialKey: String,
            clientNonce: ByteArray,
            serverNonce: ByteArray
        ): KrotSecureStream {
            val seed = buildSeed(shared, credentialKey, clientNonce, serverNonce)
            val clientToServer = hkdf(seed, "nvp1 c2s", 32)
            val serverToClient = hkdf(seed, "nvp1 s2c", 32)
            return KrotSecureStream(
                input = input,
                output = output,
                sendKey = clientToServer,
                recvKey = serverToClient
            )
        }

        private fun buildSeed(
            shared: ByteArray,
            credentialKey: String,
            clientNonce: ByteArray,
            serverNonce: ByteArray
        ): ByteArray {
            val keyBytes = Base64.getDecoder().decode(credentialKey.trim())
            return sha256(shared + keyBytes + clientNonce + serverNonce)
        }

        private fun hkdf(ikm: ByteArray, info: String, len: Int): ByteArray {
            val prk = hmac(ByteArray(32), ikm)
            val okm = ByteArray(len)
            var previous = ByteArray(0)
            var written = 0
            var counter = 1
            while (written < len) {
                previous = hmac(prk, previous + info.toByteArray(StandardCharsets.US_ASCII) + byteArrayOf(counter.toByte()))
                val take = minOf(previous.size, len - written)
                previous.copyInto(okm, destinationOffset = written, endIndex = take)
                written += take
                counter += 1
            }
            return okm
        }
    }
}

private fun encrypt(key: ByteArray, seq: Long, plain: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(seq)))
    cipher.updateAAD(associatedData(seq))
    return cipher.doFinal(plain)
}

private fun decrypt(key: ByteArray, seq: Long, encrypted: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(seq)))
    cipher.updateAAD(associatedData(seq))
    return cipher.doFinal(encrypted)
}

private fun withLabel(label: String, vararg parts: ByteArray): ByteArray =
    ByteArrayOutputStream().use { ms ->
        ms.write(label.toByteArray(StandardCharsets.US_ASCII))
        ms.write(0)
        parts.forEach(ms::write)
        ms.toByteArray()
    }

private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

private fun nonce(seq: Long): ByteArray = ByteArray(12).also { writeU64(it, 4, seq) }

private fun associatedData(seq: Long): ByteArray = ByteArray(8).also { writeU64(it, 0, seq) }

private fun readExact(input: InputStream, len: Int): ByteArray {
    val buf = ByteArray(len)
    var offset = 0
    while (offset < len) {
        val read = input.read(buf, offset, len - offset)
        if (read < 0) throw EOFException("Unexpected end of KRot stream")
        offset += read
    }
    return buf
}

private fun writeU16(value: Int): ByteArray = u16(value)

private fun u16(value: Int): ByteArray =
    byteArrayOf(((value ushr 8) and 0xff).toByte(), (value and 0xff).toByte())

private fun u16(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

private fun readU32(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xff) shl 24) or
        ((bytes[offset + 1].toInt() and 0xff) shl 16) or
        ((bytes[offset + 2].toInt() and 0xff) shl 8) or
        (bytes[offset + 3].toInt() and 0xff)

private fun writeU32(bytes: ByteArray, offset: Int, value: Long) {
    bytes[offset] = ((value ushr 24) and 0xff).toByte()
    bytes[offset + 1] = ((value ushr 16) and 0xff).toByte()
    bytes[offset + 2] = ((value ushr 8) and 0xff).toByte()
    bytes[offset + 3] = (value and 0xff).toByte()
}

private fun writeU64(bytes: ByteArray, offset: Int, value: Long) {
    for (index in 0 until 8) {
        bytes[offset + index] = ((value ushr (56 - index * 8)) and 0xff).toByte()
    }
}

private fun ByteArray.toHexLower(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}
