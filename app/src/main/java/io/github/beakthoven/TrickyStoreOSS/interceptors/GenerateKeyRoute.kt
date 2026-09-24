/*

 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>

 * SPDX-License-Identifier: GPL-3.0-or-later

 */



package io.github.beakthoven.TrickyStoreOSS.interceptors



/** Pure generateKey routing predicates; testable without binder/keystore I/O. */

object GenerateKeyRoute {

    /**
     * True when TrickyStore must synthesize a key locally instead of forwarding to real KeyMint.
     *
     * Only explicit synthetic intent forces forge: generate mode (`!` in target.txt) or AUTO when
     * [needGenerate] is true because teeBroken fallback applies. Request shape — device-ID tags
     * (Phase 3C), [KeyPurpose.ATTEST_KEY] (Phase 6F-FINAL), or an attestation-key descriptor
     * (Phase 6F) — does not imply forge for AUTO with a usable TEE.
     */
    fun computeForceForge(

        needGenerate: Boolean,

        hasDeviceIdAttestation: Boolean,

        hasAttestKeyPurpose: Boolean,

        attestationKeyDescriptorSet: Boolean,

    ): Boolean = needGenerate



    /**

     * Selects the generateKey route for intercepted callers.

     *

     * AUTO mode (target.txt without suffix): real TEE passthrough when software generation is not

     * required, regardless of whether the background probe is UNKNOWN or WORKING.

     *

     * Explicit leaf mode (target.txt `?` suffix): preserve leaf-forward certificate mutation.

     */

    fun selectTopLevelRoute(

        forceForge: Boolean,

        needHack: Boolean,

        explicitLeafHack: Boolean,

        hasAttestationChallenge: Boolean,

        needGenerate: Boolean,

    ): String {

        if (forceForge) return ROUTE_GENERATE

        if (needHack) {

            if (explicitLeafHack) {

                return if (hasAttestationChallenge) ROUTE_LEAF_FORWARD_ATTESTATION else ROUTE_LEAF_FORWARD

            }

            if (!needGenerate) {

                return ROUTE_PASSTHROUGH_REAL_TEE

            }

        }

        return ROUTE_SKIP

    }



    fun isPassthroughRoute(route: String): Boolean = route == ROUTE_PASSTHROUGH_REAL_TEE



    fun requiresCertificateMutation(route: String): Boolean =

        route == ROUTE_LEAF_FORWARD ||

            route == ROUTE_LEAF_FORWARD_ATTESTATION ||

            route == ROUTE_GENERATE



    const val ROUTE_GENERATE = "generate"

    const val ROUTE_LEAF_FORWARD = "leaf-forward"

    const val ROUTE_LEAF_FORWARD_ATTESTATION = "leaf-forward-attestation"

    const val ROUTE_PASSTHROUGH_REAL_TEE = "passthrough-real-tee"

    const val ROUTE_SKIP = "skip"

}



