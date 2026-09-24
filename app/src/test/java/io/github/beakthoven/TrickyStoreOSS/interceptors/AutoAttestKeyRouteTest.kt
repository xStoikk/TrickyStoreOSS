/*
 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.beakthoven.TrickyStoreOSS.interceptors

import android.system.keystore2.KeyDescriptor
import android.system.keystore2.KeyEntryResponse
import io.github.beakthoven.TrickyStoreOSS.CertificateGen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Phase 6F: AUTO must not treat attestationKeyDescriptor alone as synthetic generate. */
class AutoAttestKeyRouteTest {
    private companion object {
        const val UID = 10485
        const val ALIAS = "KeyAttestation"
        const val NSPACE = 0x0123456789ABCDEFL
    }

    @Before
    fun setUp() {
        PassthroughKeyRegistry.clear()
        CertificateAliasCache.keys.clear()
        CertificateAliasCache.skipLeafHacks.clear()
        CertificateAliasCache.patchedResponses.clear()
    }

    @After
    fun tearDown() {
        setUp()
    }

    private fun autoRoute(
        attestationKeyDescriptorSet: Boolean = false,
        hasDeviceIdAttestation: Boolean = false,
        hasAttestationChallenge: Boolean = true,
        explicitLeafHack: Boolean = false,
        needGenerate: Boolean = false,
    ): String {
        val forceForge =
            GenerateKeyRoute.computeForceForge(
                needGenerate = needGenerate,
                hasDeviceIdAttestation = hasDeviceIdAttestation,
                hasAttestKeyPurpose = false,
                attestationKeyDescriptorSet = attestationKeyDescriptorSet,
            )
        return GenerateKeyRoute.selectTopLevelRoute(
            forceForge = forceForge,
            needHack = true,
            explicitLeafHack = explicitLeafHack,
            hasAttestationChallenge = hasAttestationChallenge,
            needGenerate = needGenerate,
        )
    }

    @Test
    fun autoWorkingTeeWithAttestKeyDescriptorUsesPassthrough() {
        assertEquals(GenerateKeyRoute.ROUTE_PASSTHROUGH_REAL_TEE, autoRoute(attestationKeyDescriptorSet = true))
    }

    @Test
    fun autoUnknownUsableTeeWithAttestKeyDescriptorUsesPassthrough() {
        assertFalse(
            GenerateKeyRoute.computeForceForge(
                needGenerate = false,
                hasDeviceIdAttestation = false,
                hasAttestKeyPurpose = false,
                attestationKeyDescriptorSet = true,
            ),
        )
        assertEquals(GenerateKeyRoute.ROUTE_PASSTHROUGH_REAL_TEE, autoRoute(attestationKeyDescriptorSet = true))
    }

    @Test
    fun autoDeviceIdWithAttestKeyDescriptorUsesPassthrough() {
        assertEquals(
            GenerateKeyRoute.ROUTE_PASSTHROUGH_REAL_TEE,
            autoRoute(attestationKeyDescriptorSet = true, hasDeviceIdAttestation = true),
        )
    }

    @Test
    fun explicitGenerateWithAttestKeyDescriptorUsesGenerate() {
        assertTrue(
            GenerateKeyRoute.computeForceForge(
                needGenerate = true,
                hasDeviceIdAttestation = false,
                hasAttestKeyPurpose = false,
                attestationKeyDescriptorSet = true,
            ),
        )
        assertEquals(
            GenerateKeyRoute.ROUTE_GENERATE,
            autoRoute(attestationKeyDescriptorSet = true, needGenerate = true),
        )
    }

    @Test
    fun explicitGenerateWithoutAttestKeyDescriptorUsesGenerate() {
        assertEquals(
            GenerateKeyRoute.ROUTE_GENERATE,
            autoRoute(attestationKeyDescriptorSet = false, needGenerate = true),
        )
    }

    @Test
    fun explicitLeafWithAttestKeyDescriptorRemainsLeafForward() {
        assertEquals(
            GenerateKeyRoute.ROUTE_LEAF_FORWARD_ATTESTATION,
            autoRoute(attestationKeyDescriptorSet = true, explicitLeafHack = true),
        )
    }

    @Test
    fun explicitLeafWithoutAttestKeyDescriptorRemainsLeafForward() {
        assertEquals(
            GenerateKeyRoute.ROUTE_LEAF_FORWARD_ATTESTATION,
            autoRoute(attestationKeyDescriptorSet = false, explicitLeafHack = true),
        )
        assertEquals(
            GenerateKeyRoute.ROUTE_LEAF_FORWARD,
            autoRoute(
                attestationKeyDescriptorSet = true,
                explicitLeafHack = true,
                hasAttestationChallenge = false,
            ),
        )
    }

    private fun stubInfo(): CertificateAliasCache.Info {
        val kgp = CertificateGen.KeyGenParameters(emptyArray())
        return CertificateAliasCache.Info(null, null, KeyEntryResponse(), kgp)
    }

    @Test
    fun generateToAutoAttestKeyDescriptorClearsSyntheticOwnership() {
        val key = CertificateAliasCache.Key(UID, ALIAS)
        CertificateAliasCache.keys[key] = stubInfo()
        CertificateAliasCache.skipLeafHacks[key] = true

        assertFalse(
            GenerateKeyRoute.computeForceForge(
                needGenerate = false,
                hasDeviceIdAttestation = false,
                hasAttestKeyPurpose = false,
                attestationKeyDescriptorSet = true,
            ),
        )
        CertificateAliasCache.clearSyntheticCertificateState(UID, ALIAS, "transition-to-passthrough:pre")
        PassthroughKeyRegistry.promote(UID, ALIAS, NSPACE)

        assertNull(CertificateAliasCache.keys[key])
        assertFalse(CertificateAliasCache.shouldSkipLeafHackFor(UID, keyDescriptor()))
        assertTrue(CertificateAliasCache.isPassthroughDescriptor(UID, keyDescriptor()))
    }

    @Test
    fun autoAttestKeyDescriptorGetKeyEntryUsesPassthroughPolicy() {
        assertEquals(GenerateKeyRoute.ROUTE_PASSTHROUGH_REAL_TEE, autoRoute(attestationKeyDescriptorSet = true))
        PassthroughKeyRegistry.promote(UID, ALIAS, NSPACE)
        assertEquals(
            GetKeyEntryPostPolicy.Action.PASSTHROUGH_UNMODIFIED,
            GetKeyEntryPostPolicy.decide(isPassthrough = true, hasCachedPatch = false),
        )
    }

    @Test
    fun teeBrokenAutoStillUsesGenerateWhenExplicitGenerateMode() {
        assertTrue(
            GenerateKeyRoute.computeForceForge(
                needGenerate = true,
                hasDeviceIdAttestation = true,
                hasAttestKeyPurpose = false,
                attestationKeyDescriptorSet = true,
            ),
        )
    }

    @Test
    fun teeBrokenAutoWithDescriptorAloneDoesNotForceForge() {
        assertFalse(
            GenerateKeyRoute.computeForceForge(
                needGenerate = false,
                hasDeviceIdAttestation = false,
                hasAttestKeyPurpose = false,
                attestationKeyDescriptorSet = true,
            ),
        )
    }

    private fun keyDescriptor() =
        KeyDescriptor().apply {
            domain = 0
            nspace = NSPACE
            alias = ALIAS
        }
}
