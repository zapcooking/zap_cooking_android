package cooking.zap.app.ui.util

import cooking.zap.app.nostr.ProfileData

/**
 * Whether a note-level zap affordance (bolt tap, long-press quick zap, zap-poll
 * vote) may be offered for an event authored by [profile]. Mirrors the
 * send-time guard in SocialActionManager — zapping a recipient without a
 * NIP-57 lightning address (lud16) always fails with "This user has no
 * lightning address", so the affordance is hidden (not greyed) instead.
 *
 * Profile-level zaps are deliberately not gated by this: their surfaces
 * (profile screen lightning row / zap sheet) already handle a missing
 * address gracefully.
 */
fun noteZapOfferable(profile: ProfileData?): Boolean = !profile?.lud16.isNullOrBlank()
