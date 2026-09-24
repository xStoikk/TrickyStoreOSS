/*

 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>

 * SPDX-License-Identifier: GPL-3.0-or-later

 */



package io.github.beakthoven.TrickyStoreOSS.interceptors



import java.util.concurrent.CountDownLatch

import java.util.concurrent.Executors

import java.util.concurrent.TimeUnit

import org.junit.After

import org.junit.Assert.assertEquals

import org.junit.Assert.assertFalse

import org.junit.Assert.assertNotNull

import org.junit.Assert.assertNull

import org.junit.Assert.assertTrue

import org.junit.Before

import org.junit.Test



class PassthroughKeyRegistryTest {

    private companion object {

        const val UID = 10123

        const val ALIAS = "integrity.api.key.alias"

        const val NSPACE = 0x0123456789ABCDEFL

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

    fun promoteTracksKeyByAlias() {

        PassthroughKeyRegistry.promote(UID, ALIAS, null)

        assertTrue(PassthroughKeyRegistry.isTracked(PassthroughKeyRegistry.KeyId(UID, ALIAS)))

    }



    @Test

    fun promoteWithNspaceResolvesNspaceOnlyLookup() {

        PassthroughKeyRegistry.promote(UID, ALIAS, NSPACE)

        assertNotNull(PassthroughKeyRegistry.resolve(UID, ALIAS, 0L))

        assertNotNull(PassthroughKeyRegistry.resolve(UID, null, NSPACE))

    }



    @Test

    fun removeClearsAliasAndNspaceIndex() {

        PassthroughKeyRegistry.promote(UID, ALIAS, NSPACE)

        PassthroughKeyRegistry.remove(UID, ALIAS)

        assertFalse(PassthroughKeyRegistry.isTracked(PassthroughKeyRegistry.KeyId(UID, ALIAS)))

        assertNull(PassthroughKeyRegistry.resolve(UID, null, NSPACE))

    }



    @Test

    fun clearRemovesAllTrackedKeys() {

        PassthroughKeyRegistry.promote(UID, ALIAS, NSPACE)

        PassthroughKeyRegistry.promote(UID + 1, "other.alias", NSPACE + 1)

        PassthroughKeyRegistry.clear()

        assertFalse(PassthroughKeyRegistry.isTracked(PassthroughKeyRegistry.KeyId(UID, ALIAS)))

        assertFalse(PassthroughKeyRegistry.isTracked(PassthroughKeyRegistry.KeyId(UID + 1, "other.alias")))

    }



    @Test

    fun nspaceLookupRejectsWrongUid() {

        PassthroughKeyRegistry.promote(UID, ALIAS, NSPACE)

        assertNull(PassthroughKeyRegistry.resolve(UID + 1, null, NSPACE))

    }



    @Test

    fun aliasHashIsStableAndNonRevealing() {

        val hash = PassthroughKeyRegistry.aliasHash(ALIAS)

        assertEquals(PassthroughKeyRegistry.aliasHash(ALIAS), hash)

        assertFalse(hash.contains("."))

    }



    @Test

    fun concurrentPromoteRemoveResolveDoesNotCorrupt() {

        val pool = Executors.newFixedThreadPool(8)

        val start = CountDownLatch(1)

        val done = CountDownLatch(200)

        repeat(200) { i ->

            pool.submit {

                start.await()

                val alias = "alias.$i"

                PassthroughKeyRegistry.promote(UID, alias, NSPACE + i)

                PassthroughKeyRegistry.resolve(UID, alias, 0L)

                if (i % 2 == 0) PassthroughKeyRegistry.remove(UID, alias)

                done.countDown()

            }

        }

        start.countDown()

        assertTrue(done.await(10, TimeUnit.SECONDS))

        pool.shutdownNow()

    }

}



