/*
 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.beakthoven.TrickyStoreOSS.interceptors

import org.junit.Assert.assertEquals
import org.junit.Test

class GetKeyEntryPostPolicyTest {
    @Test
    fun trackedPassthroughBypassesBeforePatch() {
        assertEquals(
            GetKeyEntryPostPolicy.Action.PASSTHROUGH_UNMODIFIED,
            GetKeyEntryPostPolicy.decide(isPassthrough = true, hasCachedPatch = false),
        )
        assertEquals(
            GetKeyEntryPostPolicy.Action.PASSTHROUGH_UNMODIFIED,
            GetKeyEntryPostPolicy.decide(isPassthrough = true, hasCachedPatch = true),
        )
    }

    @Test
    fun nonPassthroughWorkingKeyUsesLeafPatch() {
        assertEquals(
            GetKeyEntryPostPolicy.Action.PATCH_LEAF,
            GetKeyEntryPostPolicy.decide(isPassthrough = false, hasCachedPatch = false),
        )
    }

    @Test
    fun cachedPatchPreferredOverLeafPatchForNonPassthrough() {
        assertEquals(
            GetKeyEntryPostPolicy.Action.SERVE_CACHED_PATCH,
            GetKeyEntryPostPolicy.decide(isPassthrough = false, hasCachedPatch = true),
        )
    }
}

