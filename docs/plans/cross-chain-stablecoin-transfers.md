# Cross-chain stablecoin transfers (USDC/USDT) — parked

Status: **future update, not started.** Decided 2026-11-27 while wrapping the
USDB balance branch (`feat/usdb-support`). The USD-mode work this branch ships
is the prerequisite; cross-chain rides directly on it.

Reference: https://docs.breez.technology/guide/cross_chain.html — glow-web's
`CrossChainWorkflow` + `useSendPayment` are the behavioral reference to port.

## What it enables

Send sats or USDB from the Spark wallet to USDC/USDT recipients on external
chains — EVM (Arbitrum, Base, and many more), Solana, Tron — and receive
USDC/USDT from those chains into the wallet. Provider: Orchestra (Flashnet).
This is the piece that lets users pay **non-Nostr parties in stablecoins**.

## State when parked

- SDK 0.28.1 (all three apps) exposes the full API surface — verified in the
  Android bindings (`getCrossChainRoutes`, `CrossChainConfig`,
  `CrossChainAddressDetails`, `CrossChainAcceptedAsset`).
- Nothing wires it up: no `crossChainConfig` at connect (every cross-chain
  call fails with `CrossChainDisabled` until set), no external-address
  parsing in the send flow, no receive flow.
- zap web: same. glow-web: has the send workflow. iOS plan doc's Phase 5
  names the same feature set as "later, optional".

## Implementation notes (from the docs, verified 2026-11-27)

- **Config**: set `crossChainConfig` at connect next to `stableBalanceConfig`;
  requires background tasks enabled (the provider leg reconciles via an SDK
  monitor + `paymentMetadataUpdated` events).
- **Send**: `parse` already recognizes bare `0x…`/Solana/`T…` addresses and
  EIP-681 / Solana Pay / TRC-20 URIs (`InputType.CrossChainAddress`). Then
  `getCrossChainRoutes` → validate amount against the route's `limits` →
  `prepareSendPayment` (quote with `expiresAt`, `maxSlippageBps` 10–500,
  fee mode) → `sendPayment`.
- **Retry safety — the sharp edge**: sends whose first leg is a token
  transfer (direct USDB source, or USDB auto-converted to sats) have **no
  idempotency**. The SDK rejects caller idempotency keys there; the app must
  debounce retries until terminal result or a double-send overpays. Sats-source
  sends are idempotent (deterministic transfer id).
- **Receive**: pick route + destination `SparkAsset` (BTC or USDB) + amount →
  SDK returns a provider-controlled deposit address for the sender; monitor
  probes for 24h past quote expiry before closing the row unfunded.
- **History**: cross-chain payments reconcile onto ONE `Payment` row whose
  `conversionInfo` (not `conversionDetails`) carries the external leg:
  status pending/completed/refundNeeded/refunded/failed, `deliveredAmount`,
  `externalTxHash`. `SparkPaymentAsset` currently reads only
  `conversionDetails` — extend the mapper to surface this status
  ("Delivering to Base… / Delivered 12.00 USDC / Refunded"), with
  `refundNeeded` prominent.
- **Mainnet only**; quote expiry forces re-prepare on send.

## Suggested sequencing

1. Android: send flow + config + history status on top of the USD-mode branch.
2. Web: port the same flows from glow-web.
3. iOS: same against its 0.28.1 bindings; absorb this doc into
   `zapcooking_ios/docs/plans/stablecoin-balance-plan.md` Phase 5.

Compliance/copy review needed before launch: sending stablecoins to an
external address is a different product promise than paying sats over
Lightning — disclose rates, slippage (default 100 bps), and provider fees.
