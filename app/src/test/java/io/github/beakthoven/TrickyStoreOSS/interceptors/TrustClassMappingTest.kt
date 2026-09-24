/*
 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.beakthoven.TrickyStoreOSS.interceptors

import org.junit.Assert.assertEquals
import org.junit.Test

class TrustClassMappingTest {
    @Test
    fun passthroughRealTeeRouteMapsHardwareTrusted() {
        val label =
            TrustClassMapping.forGenerateKeyRoute(
                selected = GenerateKeyRoute.ROUTE_PASSTHROUGH_REAL_TEE,
                forceForge = false,
                explicitLeafHack = false,
                explicitGenerate = false,
            )
        assertEquals(AttestationTrustClass.HARDWARE_PASSTHROUGH, label.trustClass)
        assertEquals("tracked-real-tee", label.reason)
    }

    @Test
    fun explicitGenerateMapsSoftwareSynthetic() {
        val label =
            TrustClassMapping.forGenerateKeyRoute(
                selected = GenerateKeyRoute.ROUTE_GENERATE,
                forceForge = true,
                explicitLeafHack = false,
                explicitGenerate = true,
            )
        assertEquals(AttestationTrustClass.SOFTWARE_SYNTHETIC, label.trustClass)
        assertEquals("explicit-generate", label.reason)
    }

    @Test
    fun brokenAutoPassthroughMapsRealKeystoreUnmodified() {
        val label =
            TrustClassMapping.forGenerateKeyRoute(
                selected = GenerateKeyRoute.ROUTE_PASSTHROUGH_REAL_TEE,
                forceForge = false,
                explicitLeafHack = false,
                explicitGenerate = false,
                teeBroken = true,
            )
        assertEquals(AttestationTrustClass.REAL_KEYSTORE_UNMODIFIED, label.trustClass)
        assertEquals("auto-broken-no-synthetic-fallback", label.reason)
    }

    @Test
    fun explicitLeafForwardMapsHybrid() {
        val label =
            TrustClassMapping.forGenerateKeyRoute(
                selected = GenerateKeyRoute.ROUTE_LEAF_FORWARD_ATTESTATION,
                forceForge = false,
                explicitLeafHack = true,
                explicitGenerate = false,
            )
        assertEquals(AttestationTrustClass.HYBRID_RE_SIGNED, label.trustClass)
        assertEquals("explicit-leaf-forward", label.reason)
    }

    @Test
    fun trackedGetKeyEntryMapsHardwarePassthrough() {
        val label =
            TrustClassMapping.forGetKeyEntryPostAction(
                GetKeyEntryPostPolicy.Action.PASSTHROUGH_UNMODIFIED,
                isPassthroughTracked = true,
            )
        assertEquals(AttestationTrustClass.HARDWARE_PASSTHROUGH, label.trustClass)
        assertEquals("tracked-real-tee", label.reason)
    }

    @Test
    fun autoUntrackedGetKeyEntryMapsRealKeystoreUnmodified() {
        val label =
            TrustClassMapping.forGetKeyEntryPostAction(
                GetKeyEntryPostPolicy.Action.PASSTHROUGH_UNMODIFIED,
                isPassthroughTracked = false,
            )
        assertEquals(AttestationTrustClass.REAL_KEYSTORE_UNMODIFIED, label.trustClass)
        assertEquals("auto-untracked-real-response", label.reason)
    }

    @Test
    fun plainAutoCurrentModeGetKeyEntryMapsCurrentModeReason() {
        val label =
            TrustClassMapping.forGetKeyEntryPostAction(
                GetKeyEntryPostPolicy.Action.PASSTHROUGH_UNMODIFIED,
                isPassthroughTracked = false,
                plainAutoCurrentMode = true,
            )
        assertEquals(AttestationTrustClass.REAL_KEYSTORE_UNMODIFIED, label.trustClass)
        assertEquals("auto-current-mode-real-response", label.reason)
    }

    @Test
    fun untrackedPassthroughUsesProvenanceSignalNotTrackHit() {
        assertEquals(
            PassthroughGetKeyEntryRegistrySignal.PROVENANCE_REAL_KEYSTORE_UNTRACKED,
            GetKeyEntryPassthroughDiag.registrySignal(isPassthroughTracked = false),
        )
    }

    @Test
    fun trackedPassthroughUsesTrackHitSignal() {
        assertEquals(
            PassthroughGetKeyEntryRegistrySignal.TRACK_HIT,
            GetKeyEntryPassthroughDiag.registrySignal(isPassthroughTracked = true),
        )
    }

    @Test
    fun explicitLeafGetKeyEntryPatchMapsHybrid() {
        val label =
            TrustClassMapping.forGetKeyEntryPostAction(
                GetKeyEntryPostPolicy.Action.PATCH_LEAF,
                isPassthroughTracked = false,
            )
        assertEquals(AttestationTrustClass.HYBRID_RE_SIGNED, label.trustClass)
        assertEquals("explicit-leaf-forward", label.reason)
    }

    @Test
    fun brokenAutoGenerateRouteUnchangedInMapping() {
        assertEquals(
            GenerateKeyRoute.ROUTE_GENERATE,
            GenerateKeyRoute.selectTopLevelRoute(
                forceForge = true,
                needHack = false,
                explicitLeafHack = false,
                hasAttestationChallenge = true,
                needGenerate = true,
            ),
        )
    }
}
