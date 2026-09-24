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

    fun decide(isPassthrough: Boolean, hasCachedPatch: Boolean): Action =
        when {
            isPassthrough -> Action.PASSTHROUGH_UNMODIFIED
            hasCachedPatch -> Action.SERVE_CACHED_PATCH
            else -> Action.PATCH_LEAF
        }
}
