# KRot Android Integration

## Connection Key

KRot profiles are imported from a single string:

```text
nora1.<base64url(json)>
```

The decoded JSON must use:

```text
schema = nora-connection-key-v1
transport_profile = tls_http_cover_v1
server.host
server.port
server.tls_name
server.cover_host
credentials.credential_id
credentials.credential_key
tunnel.client_ip
tunnel.server_ip
tunnel.cidr
tunnel.dns
```

`credential_key` is validated as standard base64. The imported profile keeps
the original key in `sourceRaw` and the decoded JSON in `normalizedJson`.

## Field Mapping

| KRot field | Android profile/runtime mapping |
|---|---|
| `server.host` / `server.port` | Server list endpoint and TCP target |
| `server.tls_name` | TLS SNI and certificate hostname |
| `server.cover_host` | HTTP/1.1 `Host` header |
| `credentials.credential_id` | Hidden credential tag input |
| `credentials.credential_key` | HMAC, HKDF seed, AES-GCM key derivation |
| `tunnel.client_ip` | Android `VpnService.Builder.addAddress` |
| `tunnel.cidr` | Address prefix for the TUN interface |
| `tunnel.dns` | Profile DNS list, with app defaults as fallback |

The visible display name is `KRot <host>:<port>`.

## Storage

KRot uses the existing Room `profiles` table. No schema migration is required
because the profile type is persisted as the enum name `KROT`, while raw and
normalized payloads reuse `sourceRaw` and `normalizedJson`.

## Runtime

`KrotBackendAdapter` is selected for `ProfileType.KROT`. It starts
`KrotVpnService`, which:

- opens a protected TCP socket to `server.host:server.port`;
- performs a TLS handshake with SNI `server.tls_name`;
- sends HTTP/1.1 `POST /assets/<random>.bin` with `Host = cover_host`;
- sends the hidden KRot bootstrap inside the HTTP body;
- verifies the server bootstrap MAC;
- derives the same traffic keys as the .NET reference;
- relays Android TUN packets as AES-256-GCM KRot records.

The Android ECDH raw shared secret is hashed with SHA-256 before KRot seed
derivation to match `.NET ECDiffieHellman.DeriveKeyMaterial`.

## Current Limits

- Only `tls_http_cover_v1` is supported.
- The relay carries IPv4 `PACKET` frames.
- Browser-grade H2/H3 cover and QUIC modes are not implemented.
- User localhost SOCKS settings are not used by KRot.
- KRot errors are reported as `KROT-101` and `KROT-102`.
