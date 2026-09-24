/*
 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.beakthoven.TrickyStoreOSS.interceptors

import android.system.keystore2.KeyDescriptor
import android.system.keystore2.KeyEntryResponse
import io.github.beakthoven.TrickyStoreOSS.CertificateGen
import io.github.beakthoven.TrickyStoreOSS.tee.TeeProbeClassifier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Phase 6K: AUTO must not silently substitute software attestation when teeBroken=true. */
class BrokenAutoRoutePolicyTest {
    private companion object {
        const val UID = 10485
        const val ALIAS = "KeyAttestation"
    }

    private enum class TargetMode {
        AUTO,
        LEAF_HACK,
        GENERATE,
    }

    /** Mirrors PkgConfig.checkNeed wiring without PackageManager. */
    private object PolicySim {
        fun needHack(mode: TargetMode, teeBroken: Boolean?): Boolean =
            when (mode) {
                TargetMode.LEAF_HACK -> true
                TargetMode.AUTO -> TeeProbeClassifier.autoLeafHackAllowed(teeBroken)
                TargetMode.GENERATE -> false
            }

        fun needGenerate(mode: TargetMode, teeBroken: Boolean?): Boolean =
            when (mode) {
                TargetMode.GENERATE -> true
                TargetMode.AUTO -> TeeProbeClassifier.autoGenerateAllowed(teeBroken)
                TargetMode.LEAF_HACK -> false
            }

        fun selectedRoute(
            mode: TargetMode,
            teeBroken: Boolean?,
            hasAttestationChallenge: Boolean = true,
            hasDeviceIdAttestation: Boolean = false,
            hasAttestKeyPurpose: Boolean = false,
            attestationKeyDescriptorSet: Boolean = false,
        ): String {
            val needGenerate = needGenerate(mode, teeBroken)
            val needHack = needHack(mode, teeBroken)
            val explicitLeaf = mode == TargetMode.LEAF_HACK
            val forceForge =
                GenerateKeyRoute.computeForceForge(
                    needGenerate = needGenerate,
                    hasDeviceIdAttestation = hasDeviceIdAttestation,
                    hasAttestKeyPurpose = hasAttestKeyPurpose,
                    attestationKeyDescriptorSet = attestationKeyDescriptorSet,
                )
            if (forceForge) return GenerateKeyRoute.ROUTE_GENERATE
            if (needHack) {
                return GenerateKeyRoute.selectTopLevelRoute(
                    forceForge = forceForge,
                    needHack = true,
                    explicitLeafHack = explicitLeaf,
                    hasAttestationChallenge = hasAttestationChallenge,
                    needGenerate = needGenerate,
                )
            }
            return GenerateKeyRoute.ROUTE_SKIP
        }
    }

    @Before
    fun setUp() {
        PassthroughKeyRegistry.clear()
        CertificateAliasCache.keys.clear()
        CertificateAliasCache.skipLeafHacks.clear()
        CertificateAliasCache.patchedResponses.clear()
    }

    @After
    fun tearDown() = setUp()

    @Test
    fun brokenAutoOrdinaryAttestationDoesNotGenerate() {
        assertEquals(
            GenerateKeyRoute.ROUTE_PASSTHROUGH_REAL_TEE,
            PolicySim.selectedRoute(TargetMode.AUTO, teeBroken = true),
        )
    }

    @Test
    fun brokenAutoAttestKeyDoesNotGenerate() {
        assertEquals(
            GenerateKeyRoute.ROUTE_PASSTHROUGH_REAL_TEE,
            PolicySim.selectedRoute(
                TargetMode.AUTO,
                teeBroken = true,
                hasAttestKeyPurpose = true,
            ),
        )
    }

    @Test
    fun brokenAutoDescriptorDoesNotGenerate() {
        assertEquals(
            GenerateKeyRoute.ROUTE_PASSTHROUGH_REAL_TEE,
            PolicySim.selectedRoute(
                TargetMode.AUTO,
                teeBroken = true,
                attestationKeyDescriptorSet = true,
            ),
        )
    }

    @Test
    fun brokenAutoDeviceIdDoesNotGenerate() {
        assertEquals(
            GenerateKeyRoute.ROUTE_PASSTHROUGH_REAL_TEE,
            PolicySim.selectedRoute(
                TargetMode.AUTO,
                teeBroken = true,
                hasDeviceIdAttestation = true,
            ),
        )
    }

    @Test
    fun brokenAutoPassthroughTrustClassDoesNotClaimHardware() {
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
    fun brokenAutoDoesNotCreateSyntheticOwnership() {
        assertFalse(PolicySim.needGenerate(TargetMode.AUTO, teeBroken = true))
        assertTrue(PolicySim.needHack(TargetMode.AUTO, teeBroken = true))
        val key = CertificateAliasCache.Key(UID, ALIAS)
        assertNull(CertificateAliasCache.keys[key])
        assertNull(CertificateAliasCache.patchedResponses[key])
        assertFalse(CertificateAliasCache.skipLeafHacks.containsKey(key))
    }

    @Test
    fun explicitGenerateWithBrokenTeeStillGenerates() {
        assertEquals(
            GenerateKeyRoute.ROUTE_GENERATE,
            PolicySim.selectedRoute(TargetMode.GENERATE, teeBroken = true),
        )
        val label =
            TrustClassMapping.forGenerateKeyRoute(
                selected = GenerateKeyRoute.ROUTE_GENERATE,
                forceForge = true,
                explicitLeafHack = false,
                explicitGenerate = true,
                teeBroken = true,
            )
        assertEquals(AttestationTrustClass.SOFTWARE_SYNTHETIC, label.trustClass)
        assertEquals("explicit-generate", label.reason)
    }

    @Test
    fun explicitLeafWithBrokenTeeRemainsHybrid() {
        assertEquals(
            GenerateKeyRoute.ROUTE_LEAF_FORWARD_ATTESTATION,
            PolicySim.selectedRoute(TargetMode.LEAF_HACK, teeBroken = true),
        )
        val label =
            TrustClassMapping.forGenerateKeyRoute(
                selected = GenerateKeyRoute.ROUTE_LEAF_FORWARD_ATTESTATION,
                forceForge = false,
                explicitLeafHack = true,
                explicitGenerate = false,
                teeBroken = true,
            )
        assertEquals(AttestationTrustClass.HYBRID_RE_SIGNED, label.trustClass)
    }

    @Test
    fun workingAutoBehaviorUnchanged() {
        assertEquals(
            GenerateKeyRoute.ROUTE_PASSTHROUGH_REAL_TEE,
            PolicySim.selectedRoute(TargetMode.AUTO, teeBroken = false),
        )
        val label =
            TrustClassMapping.forGenerateKeyRoute(
                selected = GenerateKeyRoute.ROUTE_PASSTHROUGH_REAL_TEE,
                forceForge = false,
                explicitLeafHack = false,
                explicitGenerate = false,
                teeBroken = false,
            )
        assertEquals(AttestationTrustClass.HARDWARE_PASSTHROUGH, label.trustClass)
    }

    @Test
    fun unknownAutoBehaviorUnchanged() {
        assertEquals(
            GenerateKeyRoute.ROUTE_PASSTHROUGH_REAL_TEE,
            PolicySim.selectedRoute(TargetMode.AUTO, teeBroken = null),
        )
    }

    @Test
    fun phase6jUntrackedGetKeyEntryPreservedUnderBrokenAutoNeedHack() {
        assertTrue(PolicySim.needHack(TargetMode.AUTO, teeBroken = true))
        assertFalse(PolicySim.needGenerate(TargetMode.AUTO, teeBroken = true))
        assertEquals(
            GetKeyEntryPostPolicy.Action.PASSTHROUGH_UNMODIFIED,
            GetKeyEntryPostPolicy.decide(
                GetKeyEntryPostPolicy.PostHookInput(
                    isPassthroughTracked = false,
                    hasCachedPatch = false,
                    hasGeneratedOwner = false,
                    explicitLeafHack = false,
                    autoPreserveUntrackedReal = true,
                ),
            ),
        )
    }

    @Test
    fun falsePositiveBrokenStateStillForwardsNotSynthesizes() {
        assertEquals(
            GenerateKeyRoute.ROUTE_PASSTHROUGH_REAL_TEE,
            PolicySim.selectedRoute(TargetMode.AUTO, teeBroken = true),
        )
        assertFalse(
            GenerateKeyRoute.computeForceForge(
                needGenerate = false,
                hasDeviceIdAttestation = false,
                hasAttestKeyPurpose = false,
                attestationKeyDescriptorSet = false,
            ),
        )
    }

    @Test
    fun generatedOwnerSemanticsUnchanged() {
        val key = CertificateAliasCache.Key(UID, ALIAS)
        CertificateAliasCache.keys[key] =
            CertificateAliasCache.Info(null, null, KeyEntryResponse(), CertificateGen.KeyGenParameters(emptyArray()))
        assertEquals(
            GetKeyEntryPostPolicy.Action.SERVE_CACHED_PATCH,
            GetKeyEntryPostPolicy.decide(
                GetKeyEntryPostPolicy.PostHookInput(
                    isPassthroughTracked = false,
                    hasCachedPatch = true,
                    hasGeneratedOwner = true,
                    explicitLeafHack = false,
                    autoPreserveUntrackedReal = true,
                ),
            ),
        )
    }
}
