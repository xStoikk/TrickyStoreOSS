/*
 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.beakthoven.TrickyStoreOSS.interceptors

import org.junit.Assert.assertEquals
import org.junit.Test

class GetKeyEntryPostPolicyTest {
    private fun input(
        isPassthroughTracked: Boolean = false,
        hasCachedPatch: Boolean = false,
        hasGeneratedOwner: Boolean = false,
        explicitLeafHack: Boolean = false,
        autoPreserveUntrackedReal: Boolean = false,
    ) = GetKeyEntryPostPolicy.PostHookInput(
        isPassthroughTracked = isPassthroughTracked,
        hasCachedPatch = hasCachedPatch,
        hasGeneratedOwner = hasGeneratedOwner,
        explicitLeafHack = explicitLeafHack,
        autoPreserveUntrackedReal = autoPreserveUntrackedReal,
    )

    @Test
    fun trackedPassthroughBypassesBeforePatch() {
        assertEquals(
            GetKeyEntryPostPolicy.Action.PASSTHROUGH_UNMODIFIED,
            GetKeyEntryPostPolicy.decide(input(isPassthroughTracked = true)),
        )
        assertEquals(
            GetKeyEntryPostPolicy.Action.PASSTHROUGH_UNMODIFIED,
            GetKeyEntryPostPolicy.decide(input(isPassthroughTracked = true, hasCachedPatch = true)),
        )
    }

    @Test
    fun autoWorkingUntrackedRealResponseUsesPassthrough() {
        assertEquals(
            GetKeyEntryPostPolicy.Action.PASSTHROUGH_UNMODIFIED,
            GetKeyEntryPostPolicy.decide(input(autoPreserveUntrackedReal = true)),
        )
    }

    @Test
    fun autoUnknownUntrackedRealResponseUsesPassthrough() {
        assertEquals(
            GetKeyEntryPostPolicy.Action.PASSTHROUGH_UNMODIFIED,
            GetKeyEntryPostPolicy.decide(input(autoPreserveUntrackedReal = true)),
        )
    }

    @Test
    fun explicitLeafUntrackedUsesPatchLeaf() {
        assertEquals(
            GetKeyEntryPostPolicy.Action.PATCH_LEAF,
            GetKeyEntryPostPolicy.decide(input(explicitLeafHack = true, autoPreserveUntrackedReal = false)),
        )
    }

    @Test
    fun generatedOwnerServesCachedOwner() {
        assertEquals(
            GetKeyEntryPostPolicy.Action.SERVE_CACHED_PATCH,
            GetKeyEntryPostPolicy.decide(input(hasGeneratedOwner = true)),
        )
    }

    @Test
    fun patchedOwnerPreferredOverAutoPassthrough() {
        assertEquals(
            GetKeyEntryPostPolicy.Action.SERVE_CACHED_PATCH,
            GetKeyEntryPostPolicy.decide(
                input(hasCachedPatch = true, autoPreserveUntrackedReal = true),
            ),
        )
    }

    @Test
    fun staleSyntheticGeneratedBlocksAutoUntrackedPassthrough() {
        assertEquals(
            GetKeyEntryPostPolicy.Action.SERVE_CACHED_PATCH,
            GetKeyEntryPostPolicy.decide(
                input(hasGeneratedOwner = true, autoPreserveUntrackedReal = true),
            ),
        )
    }

    @Test
    fun legacyUntrackedWithoutAutoPreserveStillPatchesLeaf() {
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

    @Test
    fun explicitLeafOverridesAutoPreserve() {
        assertEquals(
            GetKeyEntryPostPolicy.Action.PATCH_LEAF,
            GetKeyEntryPostPolicy.decide(
                input(explicitLeafHack = true, autoPreserveUntrackedReal = true),
            ),
        )
    }
}
