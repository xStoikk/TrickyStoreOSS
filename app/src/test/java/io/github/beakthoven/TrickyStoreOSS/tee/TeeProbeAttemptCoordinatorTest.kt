/*

 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>

 * SPDX-License-Identifier: GPL-3.0-or-later

 */



package io.github.beakthoven.TrickyStoreOSS.tee



import org.junit.Assert.assertEquals

import org.junit.Assert.assertNotNull

import org.junit.Assert.assertNull

import org.junit.Assert.assertTrue

import org.junit.Test



class TeeProbeAttemptCoordinatorTest {

    @Test

    fun bootstrapRunsOnceAcrossInitialAndNineRetries() {

        var bootstrapCalls = 0

        var operationCalls = 0

        val coordinator =

            TeeProbeAttemptCoordinator(

                bootstrap = { bootstrapCalls++ },

                operate = {

                    operationCalls++

                    TeeProbeResult.TRANSIENT

                },

            )



        coordinator.runOperationAttempt()

        repeat(TeeRetryPolicy.MAX_ATTEMPTS) {

            coordinator.runOperationAttempt()

        }



        assertEquals(1, bootstrapCalls)

        assertEquals(1 + TeeRetryPolicy.MAX_ATTEMPTS, operationCalls)

        assertEquals(1, coordinator.bootstrapInvocationCount)

        assertEquals(1 + TeeRetryPolicy.MAX_ATTEMPTS, coordinator.operationInvocationCount)

    }



    @Test

    fun operationCanRunMultipleTimesAfterSingleBootstrap() {

        val coordinator =

            TeeProbeAttemptCoordinator(

                bootstrap = {},

                operate = { TeeProbeResult.TRANSIENT },

            )



        repeat(3) {

            assertEquals(TeeProbeResult.TRANSIENT, coordinator.runOperationAttempt())

        }



        assertEquals(1, coordinator.bootstrapInvocationCount)

        assertEquals(3, coordinator.operationInvocationCount)

    }



    @Test

    fun transientOperationFailureAllowsLaterRetry() {

        var operationCalls = 0

        val coordinator =

            TeeProbeAttemptCoordinator(

                bootstrap = {},

                operate = {

                    operationCalls++

                    if (operationCalls < 3) TeeProbeResult.TRANSIENT else TeeProbeResult.WORKING

                },

            )



        assertEquals(TeeProbeResult.TRANSIENT, coordinator.runOperationAttempt())

        assertEquals(TeeProbeResult.TRANSIENT, coordinator.runOperationAttempt())

        assertEquals(TeeProbeResult.WORKING, coordinator.runOperationAttempt())

        assertEquals(3, operationCalls)

    }



    @Test

    fun laterOperationSuccessBecomesWorkingState() {

        var state = TeeState.UNKNOWN

        var operationCalls = 0

        val coordinator =

            TeeProbeAttemptCoordinator(

                bootstrap = {},

                operate = {

                    operationCalls++

                    if (operationCalls < 3) TeeProbeResult.TRANSIENT else TeeProbeResult.WORKING

                },

            )



        repeat(2) {

            coordinator.runOperationAttempt()?.let { state = TeeProbeClassifier.resolveState(state, it) }

        }

        assertEquals(TeeState.UNKNOWN, state)



        coordinator.runOperationAttempt()?.let { state = TeeProbeClassifier.resolveState(state, it) }

        assertEquals(TeeState.WORKING, state)

    }



    @Test

    fun bootstrapFailureDoesNotClassifyAsBroken() {

        val bootstrapError = IllegalStateException("setTelephonyServiceManager called twice!")

        val coordinator =

            TeeProbeAttemptCoordinator(

                bootstrap = { throw bootstrapError },

                operate = { TeeProbeResult.WORKING },

            )



        assertNull(coordinator.runOperationAttempt())

        assertNull(coordinator.runOperationAttempt())

        assertEquals(1, coordinator.bootstrapInvocationCount)

        assertEquals(0, coordinator.operationInvocationCount)

        assertNotNull(coordinator.lastBootstrapFailure)

        assertTrue(coordinator.lastBootstrapFailure is IllegalStateException)



        var state = TeeState.UNKNOWN

        coordinator.runOperationAttempt()?.let { state = TeeProbeClassifier.resolveState(state, it) }

        assertEquals(TeeState.UNKNOWN, state)

    }

}



