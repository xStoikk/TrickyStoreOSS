/*
 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.beakthoven.TrickyStoreOSS.interceptors

/**
 * Diagnostic provenance labels for attestation routing (Phase 6J).
 *
 * Names describe what TrickyStore knows — not unverified claims about certificate contents.
 */
enum class AttestationTrustClass {
    /** Known real-TEE generateKey ownership via [PassthroughKeyRegistry]. */
    HARDWARE_PASSTHROUGH,
    /** Unmodified binder reply from real keystore; chain trust not verified. */
    REAL_KEYSTORE_UNMODIFIED,
    SOFTWARE_SYNTHETIC,
    HYBRID_RE_SIGNED,
    UNINTERCEPTED,
}

/** Which registry/provenance diagnostic to emit for passthrough getKeyEntry delivery. */
enum class PassthroughGetKeyEntryRegistrySignal {
    TRACK_HIT,
    PROVENANCE_REAL_KEYSTORE_UNTRACKED,
}

object GetKeyEntryPassthroughDiag {
    fun registrySignal(isPassthroughTracked: Boolean): PassthroughGetKeyEntryRegistrySignal =
        if (isPassthroughTracked) {
            PassthroughGetKeyEntryRegistrySignal.TRACK_HIT
        } else {
            PassthroughGetKeyEntryRegistrySignal.PROVENANCE_REAL_KEYSTORE_UNTRACKED
        }
}

object TrustClassMapping {
    data class Label(val trustClass: AttestationTrustClass, val reason: String)

    fun forGenerateKeyRoute(
        selected: String,
        forceForge: Boolean,
        explicitLeafHack: Boolean,
        explicitGenerate: Boolean,
    ): Label =
        when {
            forceForge || selected == GenerateKeyRoute.ROUTE_GENERATE ->
                Label(
                    AttestationTrustClass.SOFTWARE_SYNTHETIC,
                    if (explicitGenerate) "explicit-generate" else "auto-broken-fallback",
                )
            explicitLeafHack ||
                selected == GenerateKeyRoute.ROUTE_LEAF_FORWARD ||
                selected == GenerateKeyRoute.ROUTE_LEAF_FORWARD_ATTESTATION ->
                Label(AttestationTrustClass.HYBRID_RE_SIGNED, "explicit-leaf-forward")
            selected == GenerateKeyRoute.ROUTE_PASSTHROUGH_REAL_TEE ->
                Label(AttestationTrustClass.HARDWARE_PASSTHROUGH, "tracked-real-tee")
            selected == GenerateKeyRoute.ROUTE_SKIP ->
                Label(AttestationTrustClass.UNINTERCEPTED, "skip")
            else -> Label(AttestationTrustClass.UNINTERCEPTED, "unknown-route")
        }

    fun forGetKeyEntryPostAction(
        action: GetKeyEntryPostPolicy.Action,
        isPassthroughTracked: Boolean,
        hasGeneratedOwner: Boolean = false,
    ): Label =
        when (action) {
            GetKeyEntryPostPolicy.Action.PASSTHROUGH_UNMODIFIED ->
                if (isPassthroughTracked) {
                    Label(AttestationTrustClass.HARDWARE_PASSTHROUGH, "tracked-real-tee")
                } else {
                    Label(AttestationTrustClass.REAL_KEYSTORE_UNMODIFIED, "auto-untracked-real-response")
                }
            GetKeyEntryPostPolicy.Action.SERVE_CACHED_PATCH ->
                if (hasGeneratedOwner) {
                    Label(AttestationTrustClass.SOFTWARE_SYNTHETIC, "generated-owner")
                } else {
                    Label(AttestationTrustClass.HYBRID_RE_SIGNED, "cached-patch-owner")
                }
            GetKeyEntryPostPolicy.Action.PATCH_LEAF ->
                Label(AttestationTrustClass.HYBRID_RE_SIGNED, "explicit-leaf-forward")
        }
}
