/*

 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>

 * SPDX-License-Identifier: GPL-3.0-or-later

 */



package io.github.beakthoven.TrickyStoreOSS.interceptors



import org.junit.After

import org.junit.Assert.assertEquals

import org.junit.Assert.assertFalse

import org.junit.Assert.assertNotNull

import org.junit.Assert.assertNull

import org.junit.Assert.assertTrue

import org.junit.Before

import org.junit.Test



/** End-to-end tracking flow for AUTO real-TEE passthrough keys (pure JVM, no Parcel I/O). */

class PassthroughGenerateKeyFlowTest {

    private companion object {

        const val UID = 10123

        const val PLAY_ALIAS = "integrity.api.key.alias"

        const val GMS_ALIAS = "device_id_attestation_key"

        const val NSPACE = 0x1234567890ABCDEFL

    }



    @Before

    fun setUp() {

        PassthroughKeyRegistry.clear()

    }



    @After

    fun tearDown() {

        PassthroughKeyRegistry.clear()

    }



    @Test

    fun autoRealTeeRouteDoesNotRequireMutation() {

        val route =

            GenerateKeyRoute.selectTopLevelRoute(

                forceForge = false,

                needHack = true,

                explicitLeafHack = false,

                hasAttestationChallenge = true,

                needGenerate = false,

            )

        assertEquals(GenerateKeyRoute.ROUTE_PASSTHROUGH_REAL_TEE, route)

        assertFalse(GenerateKeyRoute.requiresCertificateMutation(route))

    }



    @Test

    fun generateSuccessPromotesKeyForGetKeyEntryLookup() {

        PassthroughKeyRegistry.promote(UID, PLAY_ALIAS, NSPACE)

        assertNotNull(PassthroughKeyRegistry.resolve(UID, PLAY_ALIAS, 0L))

        assertNotNull(PassthroughKeyRegistry.resolve(UID, null, NSPACE))

    }



    @Test

    fun getKeyEntryPostPolicyBypassesCertificateHackForTrackedKey() {

        PassthroughKeyRegistry.promote(UID, GMS_ALIAS, NSPACE)

        val tracked = PassthroughKeyRegistry.resolve(UID, GMS_ALIAS, 0L) != null

        assertEquals(

            GetKeyEntryPostPolicy.Action.PASSTHROUGH_UNMODIFIED,

            GetKeyEntryPostPolicy.decide(isPassthrough = tracked, hasCachedPatch = false),

        )

    }



    @Test

    fun passthroughOverridesStalePatchedCache() {

        PassthroughKeyRegistry.promote(UID, PLAY_ALIAS, NSPACE)

        assertEquals(

            GetKeyEntryPostPolicy.Action.PASSTHROUGH_UNMODIFIED,

            GetKeyEntryPostPolicy.decide(isPassthrough = true, hasCachedPatch = true),

        )

    }



    @Test

    fun deleteKeyRemovesPassthroughTracking() {

        PassthroughKeyRegistry.promote(UID, PLAY_ALIAS, NSPACE)

        PassthroughKeyRegistry.remove(UID, PLAY_ALIAS)

        assertNull(PassthroughKeyRegistry.resolve(UID, PLAY_ALIAS, 0L))

        assertNull(PassthroughKeyRegistry.resolve(UID, null, NSPACE))

    }



    @Test

    fun explicitLeafRouteStillMutates() {

        val route =

            GenerateKeyRoute.selectTopLevelRoute(

                forceForge = false,

                needHack = true,

                explicitLeafHack = true,

                hasAttestationChallenge = true,

                needGenerate = false,

            )

        assertEquals(GenerateKeyRoute.ROUTE_LEAF_FORWARD_ATTESTATION, route)

        assertTrue(GenerateKeyRoute.requiresCertificateMutation(route))

    }



    @Test

    fun brokenGenerateRouteStillPatchesLeafWhenNotPassthrough() {

        assertEquals(

            GetKeyEntryPostPolicy.Action.PATCH_LEAF,

            GetKeyEntryPostPolicy.decide(isPassthrough = false, hasCachedPatch = false),

        )

    }

}



