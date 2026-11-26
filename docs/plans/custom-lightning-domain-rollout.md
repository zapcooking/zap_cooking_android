# Plan: Custom Lightning Address Domain (`@sats.zap.cooking`) + Breez SDK 0.28.1

Status: implementation complete in working trees (uncommitted at time of
writing); this issue tracks review, landing, and the release QA pass.
Companion follow-up: `docs/plans/stablecoin-balance-plan.md` in the iOS repo (zapcooking_ios).

## Context

The web app registers user Lightning addresses under `sats.zap.cooking`
instead of Breez's default `breez.tips`. `sats.zap.cooking` is a CNAME to
`breez.tips` (Breez's hosted LNURL server), registered with Breez against
the project's API key — which all three apps already share. The SDK exposes
this as `config.lnurlDomain` at connect time; registration, availability
checks, and `getLightningAddress()` then operate under the custom domain.
No backend, DNS, or Breez-side work is required.

Existing `@breez.tips` addresses keep working and displaying unchanged; the
switch only affects newly chosen addresses.

## Changes per app

### Web (zapcooking/frontend) — reference, already live
- `src/lib/spark/index.ts` sets `config.lnurlDomain = 'sats.zap.cooking'` at
  connect (breez.tips for local dev, localStorage override for testing).
- Registration sheet suffix and post-registration `lud16` sync to kind-0
  (`src/lib/spark/profileSync.ts`).

### iOS (this repo)
| File | Change |
|---|---|
| `SparkWallet.swift` | `config.lnurlDomain = BreezConfig.lnurlDomain` in `connect()`; 0.28.1 API fixes (below) |
| `BreezConfig.swift` | new `lnurlDomain` constant; `sdkVersion` mirror corrected 0.14.0 → actual pin |
| `wisp/WalletSettingsView.swift` | setup-sheet suffix now `BreezConfig.lnurlDomain`; fires post-registration profile sync |
| `WalletStore.swift` | new `syncLightningAddressToProfile()` — merges `lud16` onto latest kind-0 |
| `ProfileEditViewModel.swift` | new `hasNetworkBaseline` flag; the sync refuses to publish without a fetched kind-0 (never ships a profile holding only `lud16`) |
| `wisp/wispApp.swift`, `wisp/WalletAddressHarness.swift` | `-WalletAddressHarness` DEBUG harness hosting the real setup sheet |
| `wispTests/BreezConfigTests.swift` | domain shape test + `sdkVersion` vs `Package.resolved` mirror test |
| `wispUITests/WalletLightningAddressScreenshotsUITests.swift` | screenshot evidence → `docs/screenshots/wallet/` |

### Android (zap_cooking_android)
| File | Change |
|---|---|
| `SparkRepository.kt` | `config.lnurlDomain = LNURL_DOMAIN` in `connect()`; 0.28.1 `preimage` fix |
| `WalletScreen.kt` | registration dialog suffix `@sats.zap.cooking` |
| `res/values/strings.xml` | `wallet_get_username` copy updated |
| `gradle/libs.versions.toml` | breez-sdk-spark 0.23.1 → 0.28.1 |

Signup auto-registration (iOS `SignUpViewModel`, Android
`OnboardingViewModel`), settings flows, refresh, removal, and recovery need
no changes — they flow through the SDK and are domain-agnostic.

## Breez SDK 0.23.1 → 0.28.1

All three apps were pinned at 0.23.1; 0.28.1 is current latest. Breaking
changes found and fixed:

- `MaxFee` enum → struct `{ onchain: MaxOnchainFee, proportionalPpm? }`
  (iOS claim-deposit, web `claimDepositRequest`).
- `ReceivePaymentMethod.bolt11Invoice` gained `preimage: String?`
  (iOS + Android invoice creation).
- `PaymentDetails.withdraw` gained `vout`/`destinationAddress` (iOS
  transaction mapping).

Highlights between versions: receive USDT/USDC, Cash App off-ramp, bridging
client, token allowances, smaller mobile binaries, refresh-token auth.

## Verification performed

- iOS: `xcodebuild build` **succeeded** (iPhone 17 Pro sim); BreezConfig +
  SignUpWalletOptIn test suites **pass**; screenshot UI test **passes** and
  exports `docs/screenshots/wallet/wallet-lightning-address-setup-{idle,typed}.png`.
- Android: `./gradlew compilePlayDebugKotlin` **succeeded**.
- Web: `svelte-check` identical to the 0.23.1 baseline (18 pre-existing
  errors, zero new); 39/39 wallet/spark vitest tests pass; lockfile diff
  touches only the breez package.

## Rollout checklist

- [ ] Review + commit per repo (iOS, Android, web SDK bump)
- [ ] Manual QA on 0.28.1: connect existing wallet, send, receive, claim a
      deposit, address register/remove, cross-device `LightningAddressChanged`
- [ ] Confirm cross-domain re-registration semantics with Breez support
      (same username re-registered under sats.zap.cooking — expected to keep
      the handle and return the new suffix; unverified)
- [ ] Release notes: new addresses are `@sats.zap.cooking`; existing
      `@breez.tips` addresses unchanged
- [ ] Android screenshot capture for the registration dialog (repo has no
      hard policy; nice-to-have for parity with iOS evidence)

## Follow-up: stablecoin (USDB) balance & transactions

Tracked in `docs/plans/stablecoin-balance-plan.md` in the iOS repo (zapcooking_ios). Summary:
Phase 1 enable `stableBalanceConfig` (USDB, opt-in) + show token balance;
Phase 2 USD-mode toggle via `updateUserSettings`; Phase 3 USDB/conversion
rows in activity; Phase 4 send-entire-balance + conversion estimates;
Phase 5 optional USDT/USDC receive + Cash App off-ramp.
