# Branta Guardrail — Android Integration Plan

Status: **plan only, no code written.** Companion to
[`ZAPCOOKING_ANDROID_BUILD.md`](ZAPCOOKING_ANDROID_BUILD.md) (which wins on any
protocol/relay/backend contract). This file covers one thing: bringing the
"Verified by Branta" behaviour already live in the web client
(`zapcooking/frontend`, `src/lib/brantaService.server.ts`) onto Android now that
Branta ships a Kotlin SDK.

Everything in §1 was read out of the published artifact
(`pro.branta:branta:3.2.0` sources jar from Maven Central), not inferred from
docs.

---

## 0. The headline, before the detail

Branta lookups only return something when **some Branta platform registered
that destination**. That single fact decides the whole scope:

| Surface | Will a lookup ever light up? |
| --- | --- |
| Wallet → Send → paste/scan an invoice from a Branta-using merchant or platform | **Yes** — this is the high-value surface |
| zap.cooking's own invoices (Cheffy credits, membership, cookbook, boosts) | **Yes** — our backend already registers them via `brantaService.server.ts` |
| Zapping a random Nostr author (their Alby/LNbits/Primal invoice) | **Almost never** — nobody registered it |
| On-chain send, address pasted as plain text | **No** in `Strict` mode, by design |
| On-chain send, BIP21 QR carrying `branta_id` + `branta_secret` | **Yes** |

So: build the wallet Send path first (it is also where the existing TODO lives,
`WalletScreen.kt:5594`), make our own backend-minted invoices verifiable second,
and treat generic zap verification as a low-yield extra rather than the point.

The web app is a **receive-side (platform)** integration — it holds
`BRANTA_API_KEY` server-side and calls `addPayment`. Android is the mirror
image: a **send-side (wallet)** integration. Send-side lookups need **no API
key** (confirmed: `BrantaClient.getPayments` is an unauthenticated `GET`), so
nothing secret ships in the APK.

---

## 1. Verified facts about `pro.branta:branta:3.2.0`

Read from the sources jar. These are the constraints the design has to respect.

### 1.1 Packaging and dependencies — a clean fit

Plain **JVM jar** (`org.gradle.jvm.version = 11`), not an AAR: no manifest
merge, no resources, no `minSdk` floor of its own beyond what its APIs imply.

| SDK wants | We're on | Verdict |
| --- | --- | --- |
| `kotlin-stdlib` 2.0.21 | 2.0.21 (`libs.versions.toml:2`) | exact match |
| `okhttp` 4.12.0 | 4.12.0 (`libs.versions.toml:5`) | exact match |
| `kotlinx-serialization-json` 1.7.3 | 1.7.3 (`libs.versions.toml:6`) | exact match |
| `kotlinx-coroutines-core` 1.8.1 | 1.9.0 (`libs.versions.toml:13`) | Gradle resolves to 1.9.0, compatible |
| JVM 11 / API 26 | `jvmTarget = 17`, `minSdk = 26` | OK — and `AesEncryption` uses `java.util.Base64`, which is exactly API 26+, so 26 is the real floor |

No new transitive dependencies at all. Nothing to shade, nothing to exclude.

**R8:** the generic `kotlinx.serialization` keep rules already at
`app/proguard-rules.pro:1-17` cover `pro.branta.v2.models.*`. No new rule
should be needed — verify with `./gradlew assembleRelease` rather than assuming.

### 1.2 Public API surface

```kotlin
// pro.branta
data class BrantaClientOptions(baseUrl, defaultApiKey, hmacSecret, privacy)
// pro.branta.enums
enum BrantaServerBaseUrl { Staging, Production, Localhost }  // each carries .url
enum PrivacyMode { Strict, Loose }
enum DestinationType { BitcoinAddress, Bolt11, Bolt12, LnUrl, TetherAddress,
                       LnAddress, ArkAddress, SilentPayment }
// pro.branta.v2.interfaces.IBrantaService
suspend fun getPaymentsByQrCode(qrText, options?): PaymentsResult
suspend fun getPayments(destinationValue, destinationEncryptionKey?, options?): PaymentsResult
suspend fun addPayment(payment, options?): AddPaymentResult   // needs API key
suspend fun isApiKeyValid(options?): Boolean                  // needs API key
// pro.branta.v2.models
data class PaymentsResult(payments: List<Payment>, verifyUrl: String)
data class Payment(description, destinations, createdAt, ttl, metadata, platform,
                   platformLogoUrl, platformLogoLightUrl, parentPlatform,
                   childPlatform, btcPayServerPluginVersion, isMetadataDecrypted)
// pro.branta.exceptions
class BrantaPaymentException : Exception
class QrParseException : Exception
```

Only `getPaymentsByQrCode` and `getPayments` are in Android's scope.

### 1.3 What "zero-knowledge" actually means for us

`BrantaExtensions.getHashZkType()` defines the set of **self-encrypting**
destination types — those Branta can look up without ever seeing the plaintext:

```
bolt11 (lnbc/lntb/lnbcrt…)  ·  ark1…  ·  sp1…/tsp1…
```

For those, `getPayments` sends
`AES-GCM(lowercase(value), key = SHA256(lowercase(value)), deterministic nonce)`
as the lookup key. Branta can match it against a registration without learning
the invoice. Everything else — bitcoin address, LNURL, bolt12, lightning
address — is **not** self-encrypting and needs an out-of-band secret.

That produces the behaviour table the UI has to be written against:

| Input | Entry path | `Strict` result |
| --- | --- | --- |
| `lnbc…` bolt11 | paste **or** QR | real ZK lookup ✅ |
| bitcoin address, bare | QR | `PaymentsResult(emptyList(), verifyUrl)` — silent |
| bitcoin address, bare | paste | **throws** `BrantaPaymentException` ⚠️ |
| BIP21 with `branta_id` + `branta_secret` | QR only | real ZK lookup ✅ |
| `lnurl1…`, `lno…`, `user@domain` | either | empty / throws — never resolves directly |

Two consequences worth writing down now:

1. **The paste path throws where the QR path returns empty.** Same user intent,
   different failure mode. Every call site goes through one wrapper that
   returns "no result" for both.
2. **Lightning addresses and LNURLs are only verifiable indirectly** — resolve
   to a bolt11 first (which `WalletViewModel.resolveLightningAddress` and
   `ZapSender` already do), then verify *that*.

### 1.4 Four sharp edges in the SDK source

- **`BrantaClient` constructs its own `OkHttpClient()`** when you don't pass
  one. We inject ours:
  `BrantaService(opts, client = BrantaClient(opts, HttpClientFactory.getShortTimeoutClient()))`.
  The README says "never call `BrantaClient` directly" — constructing it for
  injection is fine; we never invoke its methods. The 5s/5s timeouts on
  `getShortTimeoutClient()` (`HttpClientFactory.kt:67`) are exactly right for a
  best-effort lookup sitting in front of a payment button.
- **`verifyLogoUrls` throws** `BrantaPaymentException` if a returned
  `platformLogoUrl` origin differs from the configured base URL origin. It's a
  security check, and it surfaces as an exception from `getPayments` — the
  wrapper must swallow it like any other failure, not crash the send flow.
- **`QrParser` needs the raw QR text.** It reads the `bitcoin:`/`lightning:`
  scheme and the BIP21 query string (`branta_id`, `branta_secret`, `lightning`,
  `bolt12`, `ark`, `silent_payment`). Our code strips those prefixes in **two**
  places before anything else sees them —
  `WalletScreen.kt:1631-1639` (gallery decode) and
  `WalletViewModel.processInput` (`WalletViewModel.kt:1221-1224`). Feed the
  parser stripped text and on-chain ZK verification silently never works.
  **This is the one change that must land before the on-chain surface can work
  at all.**
- **Dispatchers.** `BrantaClient` uses OkHttp `enqueue`, so the network hop
  doesn't block, but the SHA-256 + AES-GCM work in `getPayments`/
  `decryptDestinations` runs on the calling dispatcher. Wrap calls in
  `withContext(Dispatchers.IO)` per the repo convention.

---

## 2. Architecture

One repository, one UI component, one preference. Matches the existing
`repo/` + `ui/component/` split; no new module, no DI framework change (the app
wires ViewModels by hand in `Navigation.kt:339`).

### 2.1 `repo/BrantaRepository.kt` (new)

The only thing in the app that touches `pro.branta.*`.

```kotlin
sealed interface BrantaCheck {
    data object None : BrantaCheck                 // no result → render nothing
    data class Verified(
        val platformName: String?,
        val description: String?,
        val logoUrlDark: String?,                  // payment.platformLogoUrl
        val logoUrlLight: String?,                 // payment.platformLogoLightUrl
        val verifyUrl: String,
    ) : BrantaCheck
}

class BrantaRepository(
    private val httpClient: OkHttpClient = HttpClientFactory.getShortTimeoutClient(),
    private val prefs: BrantaPreferences,
) {
    /** Raw scanned/BIP21 text — keeps the scheme and query string intact. */
    suspend fun checkQrCode(rawQrText: String): BrantaCheck

    /** Typed/pasted/derived value (bolt11, address). */
    suspend fun checkDestination(value: String): BrantaCheck
}
```

Contract, non-negotiable, mirroring the SDK's own integration rules:

- Every path returns `BrantaCheck.None` on empty result **or any exception**
  (`BrantaPaymentException`, `QrParseException`, `IOException`,
  `SerializationException`, timeout). `CancellationException` rethrows.
- Never emits an error string, never emits a "not verified" state. Absence of a
  Branta card means "unknown", and the UI must not editorialise that into a
  warning — an unregistered invoice is the overwhelmingly common case, and
  scaring users about it would be actively wrong.
- `PrivacyMode.Strict` always, unless `prefs.onChainLookupOptIn` is true, which
  swaps in `Loose` **for that call only** via the per-call `options` override.
- `BrantaServerBaseUrl.Production`; `Staging` selectable from a debug-only hook
  so the flavor split doesn't need to know about Branta.

### 2.2 `repo/BrantaPreferences.kt` (new)

Same shape as `SafetyPreferences` — `SharedPreferences` + `StateFlow`, per-account file:

- `guardrailEnabled: StateFlow<Boolean>` — master switch, **default true**.
  Strict-mode lookups are ZK, so on-by-default is defensible; users who don't
  want any third-party round trip get one switch.
- `onChainLookupOptIn: StateFlow<Boolean>` — **default false**. Enables
  `PrivacyMode.Loose` so bare on-chain addresses can be checked, at the cost of
  sending the plaintext address to Branta. Copy must say that plainly.

Both surface in `WalletSettingsContent` (`WalletScreen.kt:4049`).

### 2.3 `ui/component/BrantaVerifiedCard.kt` (new)

Renders **only** `BrantaCheck.Verified`. Per Branta's display rules: platform
logo, payment description, and the `verifyUrl` as a tappable link. Logo variant
follows the theme — `logoUrlDark` on dark, `logoUrlLight` on light (Coil is
already a dependency). No card at all for `None`.

---

## 3. Phases

Stop-gated, same convention as `ZAPCOOKING_ANDROID_BUILD.md`. Each phase is
independently shippable.

### Phase 1 — Dependency + repository + tests (no UI)

1. `libs.versions.toml`: `branta = "3.2.0"` and
   `branta = { group = "pro.branta", name = "branta", version.ref = "branta" }`.
2. `app/build.gradle.kts`: `implementation(libs.branta)`.
3. `BrantaRepository`, `BrantaPreferences`, `BrantaCheck`.
4. JVM unit tests in `app/src/test/.../repo/BrantaRepositoryTest.kt`, driving a
   fake `IBrantaService` (the interface exists precisely for this) plus
   `MockWebServer` for one end-to-end wire test:
   - bolt11 paste → `Verified` populated from `PaymentsResult`
   - empty `payments` → `None` (and `verifyUrl` **not** rendered)
   - `BrantaPaymentException` from a strict plain-address lookup → `None`
   - logo-origin-mismatch exception → `None`
   - HTTP 500 / timeout → `None`
   - `guardrailEnabled = false` → `None` without any network call
   - `onChainLookupOptIn = true` → `Loose` reaches the per-call options
5. Confirm `./gradlew assembleRelease` is clean (R8 keep rules).

Gate: `./gradlew testZapstoreDebugUnitTest` green, release build clean.

### Phase 2 — Wallet Send (the surface with the existing TODO)

This is where the value is, and it needs the raw-QR fix.

1. **Preserve raw scan text.** Add a `rawScanText: String?` alongside
   `_sendInput` in `WalletViewModel`. Populate it in both scan entry points —
   `ScanQRContent`'s `onResult` (`WalletScreen.kt:1786`) and the gallery
   decoder (`WalletScreen.kt:1611-1642`) — *before* prefix stripping. Leave the
   existing stripping for the parse/pay path untouched; this is additive.
2. `WalletViewModel`: a `brantaCheck: StateFlow<BrantaCheck>` fired when the
   flow reaches a confirm screen. Use `checkQrCode(rawScanText)` when the
   destination came from a scan, `checkDestination(value)` otherwise. Clear it
   on every navigation away, and cancel the in-flight job — a stale card from a
   previous invoice on a new confirm screen is the one genuinely dangerous bug
   in this feature.
3. Render `BrantaVerifiedCard` in:
   - `SendConfirmContent` (`WalletScreen.kt:1881`) — above the summary `Card`
   - `OnchainSendConfirmContent` (`WalletScreen.kt:5804`) — the TODO's
     "below the detection note" slot (`WalletScreen.kt:5594`)
4. Never block or delay the Pay button on the lookup. It resolves late or not
   at all; the card just appears.
5. Settings rows for both preferences in `WalletSettingsContent`.
6. Remove the now-satisfied TODO comment.

Gate: manual pass — bolt11 from a Branta platform shows the card; a random
Alby invoice shows nothing; bare on-chain address shows nothing in Strict and
the card in Loose; BIP21-with-`branta_secret` QR shows the card in Strict.

### Phase 3 — zap.cooking's own invoices

Our backend already registers these, so they resolve today with zero backend
work. Look up by bolt11 (self-encrypting → Strict is fine) and show the card:

- Cheffy credit invoice — `NoteReviewSheet.kt:509-606` (the PAYING screen,
  which already displays `invoice.bolt11` and its QR). Strongest single demo of
  the feature: our own platform name and logo, on our own invoice.
- Any other backend-minted invoice reached from Android (membership, cookbook,
  boosts) as those land.

Gate: the credit-invoice screen shows a zap.cooking-branded card against
production.

### Phase 4 — Zap and content-pill paths (optional, low yield)

Only worth doing once 1–3 are in, and worth being honest that it will usually
show nothing:

- `ZapDialog` / `ZapSender.sendZap` — verify the bolt11 returned by the LNURL
  callback, after resolution, before payment.
- `LightningPaySheet` (`ui/component/LightningPaySheet.kt`) — invoice and
  LNURL pills in note content (`RichContent.kt:470-471`, `696-741`), again
  post-resolution.

Open product question for this phase, flagged rather than decided: whether to
fire a lookup on **every** zap. It adds a third-party HTTP round trip that
reveals "this user is about to pay someone" (IP + timing, though not the
invoice) on every tap of the zap button. Options: always on, gate above a sats
threshold, or leave zap-path verification off by default. Recommendation:
**gate by amount** — reuse the existing `PAY_SOFT_CONFIRM_SATS = 10_000`
threshold from `LightningPaySheet.kt:67` so the check runs exactly where a
confirm step already exists.

### Phase 5 — Receive side (deferred, needs backend)

Registering the Spark wallet's *own* invoices and the user's
`@zap.cooking` lightning address would let other Branta-aware wallets verify
payments to our users. `addPayment` requires `BRANTA_API_KEY`, which **must not
ship in the APK** — an extractable platform key would let anyone register
destinations as zap.cooking, which is strictly worse than not doing this at
all.

Correct shape: a NIP-98-authenticated proxy on the existing backend
(`POST /api/branta/register`, alongside the endpoints in `api/ZapCookingApi.kt`)
that calls the already-present `registerPayment()` in
`brantaService.server.ts` and returns `{ verifyUrl, secret }`. Android calls
that, never Branta directly. Gated behind "account has a signing key"
(`READ_ONLY` accounts can't NIP-98). Blocked on a backend change and out of
scope for this plan; noted so the boundary is explicit.

---

## 4. UI/UX rules (from Branta's own integration guidance)

1. Show the card **only** on a non-empty result. No result → render nothing.
2. Never show an error, a spinner-turned-failure, or a "not verified" badge.
   An unregistered destination is normal, not suspicious.
3. When verified, show: platform logo, payment description, `verifyUrl` as a
   tappable link.
4. `platformLogoUrl` on dark backgrounds, `platformLogoLightUrl` on light.
5. `verifyUrl` is returned even when `payments` is empty — **don't** render it
   in that case.

Copy should track the web client's wording so the two surfaces read as one
product; worth a look at the frontend's verification component before writing
Android strings.

---

## 5. Risks

| Risk | Handling |
| --- | --- |
| Stale card carried onto a new confirm screen | Cancel + clear on every navigation; assert in a ViewModel test. The one high-severity bug available here. |
| Strict-mode paste throws where QR returns empty | Single wrapper collapses both to `None`; unit-tested. |
| Raw-QR stripping silently disables on-chain verification | Phase 2 step 1, before anything else in that phase. |
| Users read "no card" as "unsafe" | No negative state anywhere in the UI; settings copy explains what the card means. |
| Third-party lookup leaks payment timing/IP | Strict (ZK) by default; master switch; on-chain plaintext strictly opt-in; amount gate on the zap path. |
| Branta down or slow | 5s/5s client, fully non-blocking, `None` on failure — payments never gated on Branta. |
| SDK is at `3.2.0`, one published version, dated 2026-07-25 | Pin the exact version in `libs.versions.toml`; don't float. |

---

## 6. Open questions for the team

1. **Zap-path verification** — always, amount-gated (recommended), or off by
   default? Only genuine product call here.
2. **Loose-mode on-chain opt-in** — ship the toggle in Phase 2, or hold on-chain
   verification to BIP21-with-`branta_secret` QRs only and skip Loose entirely?
   (Shipping the toggle is the recommendation: it's off by default and the
   copy can be explicit.)
3. **Web parity for copy/branding** — is there an existing verification
   component in `zapcooking/frontend` whose wording Android should match
   verbatim? Worth confirming before writing strings.
4. **Phase 5 backend endpoint** — is registering Android-minted Spark invoices
   wanted at all, or does receive-side stay a web-only concern?
