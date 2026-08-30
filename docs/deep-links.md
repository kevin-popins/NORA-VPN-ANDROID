# NORA VPN Android deep links

This document defines the public deep-link contract for importing a subscription or a single VPN profile into NORA VPN Android.

## Contract

- Scheme: `noravpn`
- Host: `import`
- Version: `1`
- Import types: `subscription`, `profile`

The application accepts `ACTION_VIEW` links in these forms:

```text
noravpn://import/subscription?v=1&url=<percent-encoded-http-url>
noravpn://import/profile?v=1&data=<percent-encoded-profile>
```

An optional subscription name can be supplied with `name`:

```text
noravpn://import/subscription?v=1&url=<percent-encoded-http-url>&name=<percent-encoded-name>
```

For payloads where nested query strings or fragments make URL construction inconvenient, use unpadded Base64URL (`RFC 4648`, URL-safe alphabet) either in the path or in the `payload` query parameter:

```text
noravpn://import/subscription/<base64url-utf8-url>?v=1
noravpn://import/profile/<base64url-utf8-profile>?v=1
noravpn://import/subscription?v=1&payload=<base64url-utf8-url>
noravpn://import/profile?v=1&payload=<base64url-utf8-profile>
```

Supply exactly one payload. Do not combine a path payload with `url`, `data`, or `payload`.

## Dashboard integration

For a subscription URL, percent-encoding is sufficient:

```javascript
function noraSubscriptionDeepLink(subscriptionUrl, displayName) {
  const params = new URLSearchParams({
    v: "1",
    url: subscriptionUrl,
  });

  if (displayName) params.set("name", displayName);
  return `noravpn://import/subscription?${params.toString()}`;
}
```

For a profile, Base64URL avoids ambiguity around `#`, `&`, `+`, and nested query parameters:

```javascript
function utf8Base64Url(value) {
  const bytes = new TextEncoder().encode(value);
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);

  return btoa(binary)
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/g, "");
}

function noraProfileDeepLink(profile) {
  return `noravpn://import/profile/${utf8Base64Url(profile)}?v=1`;
}
```

Example button:

```html
<a href="noravpn://import/subscription?v=1&amp;url=https%3A%2F%2Fexample.com%2Fsub%2Fdemo">
  Add to NORA VPN
</a>
```

## Accepted payloads

Subscription deep links accept only absolute `http://` or `https://` URLs. Profile deep links use the same profile importer as the Add screen, including NORA/KRot `nora1.`, VLESS, VMess, Trojan, HAPP crypt5, Xray JSON, and AmneziaWG configuration payloads.

## Validation and limits

- Deep-link version must be omitted or equal to `1`.
- Complete deep-link limit: 384 KiB before decoding.
- Payload text must be valid UTF-8.
- NUL and non-text control characters are rejected.
- Profile payload limit: 256 KiB after decoding.
- Subscription URL limit: 8 KiB after decoding.
- Subscription names are optional and limited to 256 UTF-8 bytes.
- Duplicate, unknown, conflicting, malformed, or unsupported parameters are rejected.
- Subscription URLs containing embedded `user:password@host` credentials or fragments are rejected.

Base64URL is encoding, not encryption. Do not place reusable credentials in analytics, logs, referrer parameters, or public pages. Prefer short-lived subscription URLs issued to the authenticated dashboard user.

The `noravpn` custom scheme is not domain-verified and can be claimed by another Android application. Do not use the link itself as an authentication mechanism. A future verified HTTPS App Link requires a stable dashboard domain and its `/.well-known/assetlinks.json` association.

## Android behavior

The link works on a cold start and while NORA VPN is already running. A running app receives the new link through `MainActivity.onNewIntent()`. The payload is then passed to the existing subscription/profile import pipeline; the deep link does not start or stop VPN itself.

Manual device checks:

```bash
adb shell am start -W -a android.intent.action.VIEW \
  -d 'noravpn://import/subscription?v=1&url=https%3A%2F%2Fexample.com%2Fsub%2Fdemo'

adb shell am start -W -a android.intent.action.VIEW \
  -d 'noravpn://import/profile?v=1&data=nora1.test-payload'
```

The dashboard should show a normal web fallback when Android reports that no application can handle the custom scheme.
