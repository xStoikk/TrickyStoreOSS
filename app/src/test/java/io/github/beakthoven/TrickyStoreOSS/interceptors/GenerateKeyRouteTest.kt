/*

 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>

 * SPDX-License-Identifier: GPL-3.0-or-later

 */



package io.github.beakthoven.TrickyStoreOSS.interceptors



import org.junit.Assert.assertEquals

import org.junit.Assert.assertFalse

import org.junit.Assert.assertTrue

import org.junit.Test



class GenerateKeyRouteTest {

    @Test

    fun autoUnknownUsesRealTeePassthrough() {

        assertEquals(

            GenerateKeyRoute.ROUTE_PASSTHROUGH_REAL_TEE,

            GenerateKeyRoute.selectTopLevelRoute(

                forceForge = false,

                needHack = true,

                explicitLeafHack = false,

                hasAttestationChallenge = true,

                needGenerate = false,

            ),

        )

        assertFalse(GenerateKeyRoute.requiresCertificateMutation(GenerateKeyRoute.ROUTE_PASSTHROUGH_REAL_TEE))

        assertTrue(GenerateKeyRoute.isPassthroughRoute(GenerateKeyRoute.ROUTE_PASSTHROUGH_REAL_TEE))

    }



    @Test

    fun autoWorkingUsesRealTeePassthrough() {

        assertEquals(

            GenerateKeyRoute.ROUTE_PASSTHROUGH_REAL_TEE,

            GenerateKeyRoute.selectTopLevelRoute(

                forceForge = false,

                needHack = true,

                explicitLeafHack = false,

                hasAttestationChallenge = true,

                needGenerate = false,

            ),

        )

    }



    @Test

    fun autoGmsDeviceIdUsesRealTeePassthrough() {

        assertFalse(

            GenerateKeyRoute.computeForceForge(

                needGenerate = false,

                hasDeviceIdAttestation = true,

                hasAttestKeyPurpose = false,

                attestationKeyDescriptorSet = false,

            ),

        )

        assertEquals(

            GenerateKeyRoute.ROUTE_PASSTHROUGH_REAL_TEE,

            GenerateKeyRoute.selectTopLevelRoute(

                forceForge = false,

                needHack = true,

                explicitLeafHack = false,

                hasAttestationChallenge = true,

                needGenerate = false,

            ),

        )

    }



    @Test

    /** needGenerate=true reflects explicit `!` on UID, not BROKEN AUTO silent fallback. */
    fun explicitNeedGenerateForcesGenerateRoute() {

        assertTrue(

            GenerateKeyRoute.computeForceForge(

                needGenerate = true,

                hasDeviceIdAttestation = true,

                hasAttestKeyPurpose = false,

                attestationKeyDescriptorSet = false,

            ),

        )

        assertEquals(

            GenerateKeyRoute.ROUTE_GENERATE,

            GenerateKeyRoute.selectTopLevelRoute(

                forceForge = true,

                needHack = true,

                explicitLeafHack = false,

                hasAttestationChallenge = true,

                needGenerate = true,

            ),

        )

    }



    @Test

    fun explicitLeafModeUsesLeafForwardAttestation() {

        assertEquals(

            GenerateKeyRoute.ROUTE_LEAF_FORWARD_ATTESTATION,

            GenerateKeyRoute.selectTopLevelRoute(

                forceForge = false,

                needHack = true,

                explicitLeafHack = true,

                hasAttestationChallenge = true,

                needGenerate = false,

            ),

        )

        assertTrue(

            GenerateKeyRoute.requiresCertificateMutation(GenerateKeyRoute.ROUTE_LEAF_FORWARD_ATTESTATION),

        )

    }



    @Test

    fun explicitLeafModeWithoutChallengeUsesLeafForward() {

        assertEquals(

            GenerateKeyRoute.ROUTE_LEAF_FORWARD,

            GenerateKeyRoute.selectTopLevelRoute(

                forceForge = false,

                needHack = true,

                explicitLeafHack = true,

                hasAttestationChallenge = false,

                needGenerate = false,

            ),

        )

    }



    @Test

    fun autoWithNeedGenerateUsesGenerate() {

        assertEquals(

            GenerateKeyRoute.ROUTE_GENERATE,

            GenerateKeyRoute.selectTopLevelRoute(

                forceForge = true,

                needHack = true,

                explicitLeafHack = false,

                hasAttestationChallenge = true,

                needGenerate = true,

            ),

        )

    }



    @Test

    fun autoAttestKeyPurposeDoesNotForceForge() {

        assertFalse(

            GenerateKeyRoute.computeForceForge(

                needGenerate = false,

                hasDeviceIdAttestation = false,

                hasAttestKeyPurpose = true,

                attestationKeyDescriptorSet = false,

            ),

        )

        assertEquals(

            GenerateKeyRoute.ROUTE_PASSTHROUGH_REAL_TEE,

            GenerateKeyRoute.selectTopLevelRoute(

                forceForge = false,

                needHack = true,

                explicitLeafHack = false,

                hasAttestationChallenge = true,

                needGenerate = false,

            ),

        )

    }



    @Test

    fun explicitGenerateWithAttestKeyPurposeUsesGenerate() {

        assertTrue(

            GenerateKeyRoute.computeForceForge(

                needGenerate = true,

                hasDeviceIdAttestation = false,

                hasAttestKeyPurpose = true,

                attestationKeyDescriptorSet = false,

            ),

        )

    }



    @Test

    fun regression_autoDescriptorMustNotForge() {

        assertFalse(

            GenerateKeyRoute.computeForceForge(

                needGenerate = false,

                hasDeviceIdAttestation = false,

                hasAttestKeyPurpose = false,

                attestationKeyDescriptorSet = true,

            ),

        )

        assertEquals(

            GenerateKeyRoute.ROUTE_PASSTHROUGH_REAL_TEE,

            GenerateKeyRoute.selectTopLevelRoute(

                forceForge = false,

                needHack = true,

                explicitLeafHack = false,

                hasAttestationChallenge = true,

                needGenerate = false,

            ),

        )

    }



    @Test

    fun phase3cRegressionDeviceIdAloneDoesNotForceForge() {

        assertFalse(

            GenerateKeyRoute.computeForceForge(

                needGenerate = false,

                hasDeviceIdAttestation = true,

                hasAttestKeyPurpose = false,

                attestationKeyDescriptorSet = false,

            ),

        )

    }

}



