/*
 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.beakthoven.TrickyStoreOSS.interceptors

/** Pure decision logic for getKeyEntry post-hook certificate handling. */
object GetKeyEntryPostPolicy {
    enum class Action {
        /** Return the real keystore reply without reading or mutating certificate data. */
        PASSTHROUGH_UNMODIFIED,

        /** Serve a previously patched in-memory response. */
        SERVE_CACHED_PATCH,

        /** Parse the reply and apply CertificateHack leaf patching. */
        PATCH_LEAF,
    }

    data class PostHookInput(
        val isPassthroughTracked: Boolean,
        val hasCachedPatch: Boolean,
        val hasGeneratedOwner: Boolean,
        val explicitLeafHack: Boolean,
        val autoPreserveUntrackedReal: Boolean,
    )

    fun decide(input: PostHookInput): Action =
        when {
            input.isPassthroughTracked -> Action.PASSTHROUGH_UNMODIFIED
            input.hasCachedPatch || input.hasGeneratedOwner -> Action.SERVE_CACHED_PATCH
            input.explicitLeafHack -> Action.PATCH_LEAF
            input.autoPreserveUntrackedReal -> Action.PASSTHROUGH_UNMODIFIED
            else -> Action.PATCH_LEAF
        }

    /** Legacy two-flag API; defaults preserve pre-6J explicit-leaf-only fallback semantics. */
    fun decide(isPassthrough: Boolean, hasCachedPatch: Boolean): Action =
        decide(
            PostHookInput(
                isPassthroughTracked = isPassthrough,
                hasCachedPatch = hasCachedPatch,
                hasGeneratedOwner = false,
                explicitLeafHack = false,
                autoPreserveUntrackedReal = false,
            ),
        )
}
