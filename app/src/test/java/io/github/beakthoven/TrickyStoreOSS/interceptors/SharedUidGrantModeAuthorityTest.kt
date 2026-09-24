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

import org.junit.Assert.assertNotNull

import org.junit.Assert.assertNull

import org.junit.Assert.assertTrue

import org.junit.Before

import org.junit.Test



/** Phase 6K-CLOSEOUT: shared-UID aggregate mode authority and grant-domain policy. */

class SharedUidGrantModeAuthorityTest {

    private companion object {

        const val UID_SHARED = 10485

        const val UID_OWNER = 10486

        const val UID_GRANTEE = 10487

        const val ALIAS = "KeyAttestation"

        const val GRANT_NSPACE = 0x0FEDCBA987654321L

    }



    private enum class TargetMode {

        AUTO,

        LEAF_HACK,

        GENERATE,

    }



    /** Mirrors PkgConfig.checkNeed / isPlainAuto / explicit flags for multi-package UIDs. */

    private object UidAggregate {

        fun checkNeed(

            packageModes: List<TargetMode?>,

            targetMode: TargetMode,

            autoPredicate: Boolean,

        ): Boolean {

            for (mode in packageModes) {

                when (mode) {
                    targetMode -> return true
                    TargetMode.AUTO -> if (autoPredicate) return true
                    null -> {}
                    else -> {}
                }

            }

            return false

        }



        fun needHack(packageModes: List<TargetMode?>, teeBroken: Boolean? = false) =

            checkNeed(

                packageModes,

                TargetMode.LEAF_HACK,

                TeeProbeClassifier.autoLeafHackAllowed(teeBroken),

            )



        fun needGenerate(packageModes: List<TargetMode?>, teeBroken: Boolean? = false) =

            checkNeed(

                packageModes,

                TargetMode.GENERATE,

                TeeProbeClassifier.autoGenerateAllowed(teeBroken),

            )



        fun isExplicitGenerate(packageModes: List<TargetMode?>) =

            packageModes.any { it == TargetMode.GENERATE }



        fun isExplicitLeafHack(packageModes: List<TargetMode?>) =

            packageModes.any { it == TargetMode.LEAF_HACK }



        fun isPlainAuto(packageModes: List<TargetMode?>) =

            !isExplicitGenerate(packageModes) &&

                !isExplicitLeafHack(packageModes) &&

                packageModes.any { it == TargetMode.AUTO }

    }



    @Before

    fun setUp() = resetState()



    @After

    fun tearDown() = resetState()



    private fun resetState() {

        CertificateAliasCache.keys.clear()

        CertificateAliasCache.patchedResponses.clear()

        CertificateAliasCache.skipLeafHacks.clear()

        CertificateAliasCache.keysByNspace.clear()

        CertificateAliasCache.grants.clear()

        PassthroughKeyRegistry.clear()

    }



    private fun descriptor(

        uid: Int = UID_SHARED,

        alias: String = ALIAS,

        nspace: Long = 0L,

    ): KeyDescriptor =

        KeyDescriptor().apply {

            domain = 0

            this.nspace = nspace

            this.alias = alias

        }



    private fun seedGenerated(uid: Int, alias: String = ALIAS) {

        val k = CertificateAliasCache.Key(uid, alias)

        CertificateAliasCache.keys[k] =

            CertificateAliasCache.Info(

                null,

                null,

                KeyEntryResponse(),

                CertificateGen.KeyGenParameters(emptyArray()),

            )

        CertificateAliasCache.skipLeafHacks[k] = true

    }



    private fun seedPatched(uid: Int, alias: String = ALIAS) {

        CertificateAliasCache.patchedResponses[CertificateAliasCache.Key(uid, alias)] = KeyEntryResponse()

    }



    private fun registerGrant(

        ownerUid: Int,

        granteeUid: Int,

        alias: String = ALIAS,

        grantId: Long = GRANT_NSPACE,

    ) {

        val key = CertificateAliasCache.Key(ownerUid, alias)

        CertificateAliasCache.grants[grantId] =

            CertificateAliasCache.GrantInfo(ownerUid, granteeUid, key, accessVector = 4)

    }



    // --- single-package baselines (1–3) ---



    @Test

    fun singlePackageAutoUnchanged() {

        val modes = listOf(TargetMode.AUTO)

        assertTrue(UidAggregate.needHack(modes))

        assertFalse(UidAggregate.needGenerate(modes))

        assertTrue(UidAggregate.isPlainAuto(modes))

        assertFalse(UidAggregate.isExplicitGenerate(modes))

        assertFalse(UidAggregate.isExplicitLeafHack(modes))

    }



    @Test

    fun singlePackageExplicitGenerateUnchanged() {

        val modes = listOf(TargetMode.GENERATE)

        assertFalse(UidAggregate.needHack(modes))

        assertTrue(UidAggregate.needGenerate(modes))

        assertFalse(UidAggregate.isPlainAuto(modes))

        assertTrue(UidAggregate.isExplicitGenerate(modes))

    }



    @Test

    fun singlePackageExplicitLeafUnchanged() {

        val modes = listOf(TargetMode.LEAF_HACK)

        assertTrue(UidAggregate.needHack(modes))

        assertFalse(UidAggregate.needGenerate(modes))

        assertFalse(UidAggregate.isPlainAuto(modes))

        assertTrue(UidAggregate.isExplicitLeafHack(modes))

    }



    // --- shared UID matrix (4–6) ---



    @Test

    fun sharedUidAutoPlusExplicitGenerateElevatesUid() {

        val modes = listOf(TargetMode.AUTO, TargetMode.GENERATE)

        assertTrue(UidAggregate.needGenerate(modes))

        assertTrue(UidAggregate.needHack(modes))

        assertFalse(UidAggregate.isPlainAuto(modes))

        assertTrue(UidAggregate.isExplicitGenerate(modes))

    }



    @Test

    fun sharedUidAutoPlusExplicitLeafElevatesUid() {

        val modes = listOf(TargetMode.AUTO, TargetMode.LEAF_HACK)

        assertFalse(UidAggregate.needGenerate(modes))

        assertTrue(UidAggregate.needHack(modes))

        assertFalse(UidAggregate.isPlainAuto(modes))

        assertTrue(UidAggregate.isExplicitLeafHack(modes))

    }



    @Test

    fun sharedUidMixedModesDeterministicRegardlessPackageOrder() {

        val autoGenerate = listOf(TargetMode.AUTO, TargetMode.GENERATE)

        val generateAuto = listOf(TargetMode.GENERATE, TargetMode.AUTO)

        val autoLeaf = listOf(TargetMode.AUTO, TargetMode.LEAF_HACK)

        val leafAuto = listOf(TargetMode.LEAF_HACK, TargetMode.AUTO)

        val allThree = listOf(TargetMode.AUTO, TargetMode.GENERATE, TargetMode.LEAF_HACK)

        val allThreeShuffled = listOf(TargetMode.LEAF_HACK, TargetMode.AUTO, TargetMode.GENERATE)



        assertEquals(UidAggregate.needGenerate(autoGenerate), UidAggregate.needGenerate(generateAuto))

        assertEquals(UidAggregate.needHack(autoGenerate), UidAggregate.needHack(generateAuto))

        assertEquals(UidAggregate.isPlainAuto(autoGenerate), UidAggregate.isPlainAuto(generateAuto))



        assertEquals(UidAggregate.needHack(autoLeaf), UidAggregate.needHack(leafAuto))

        assertEquals(UidAggregate.isPlainAuto(autoLeaf), UidAggregate.isPlainAuto(leafAuto))



        assertEquals(UidAggregate.needGenerate(allThree), UidAggregate.needGenerate(allThreeShuffled))

        assertEquals(UidAggregate.needHack(allThree), UidAggregate.needHack(allThreeShuffled))

        assertEquals(UidAggregate.isPlainAuto(allThree), UidAggregate.isPlainAuto(allThreeShuffled))

        assertFalse(UidAggregate.isPlainAuto(allThree))

    }



    @Test

    fun sharedUidAutoOnlyInvalidatesHistoricalGenerated() {

        seedGenerated(UID_SHARED)

        assertTrue(

            GetKeyEntryCurrentModeAuthority.rejectHistoricalOwners(UID_SHARED, descriptor(UID_SHARED)),

        )

        assertNull(CertificateAliasCache.findGeneratedKey(UID_SHARED, descriptor(UID_SHARED)))

    }



    @Test

    fun sharedUidAutoPlusGenerateDoesNotClaimPlainAutoInvalidation() {

        seedGenerated(UID_SHARED)

        assertFalse(UidAggregate.isPlainAuto(listOf(TargetMode.AUTO, TargetMode.GENERATE)))

        assertFalse(GetKeyEntryCurrentModeAuthority.shouldForwardRealKeystore(plainAutoMode = false))

        assertNotNull(CertificateAliasCache.findGeneratedKey(UID_SHARED, descriptor(UID_SHARED)))

    }



    @Test

    fun sharedUidAutoPlusLeafDoesNotClaimPlainAutoInvalidation() {

        seedPatched(UID_SHARED)

        assertFalse(UidAggregate.isPlainAuto(listOf(TargetMode.AUTO, TargetMode.LEAF_HACK)))

        assertNull(

            if (GetKeyEntryCurrentModeAuthority.shouldForwardRealKeystore(false)) {

                "unexpected"

            } else {

                null

            },

        )

        assertNotNull(CertificateAliasCache.resolvePatchedResponse(UID_SHARED, descriptor(UID_SHARED)))

    }



    // --- grant domain (7–10) ---



    @Test

    fun regression_plainAutoGrantMustNotServeSyntheticOwner() {

        seedGenerated(UID_OWNER)

        registerGrant(UID_OWNER, UID_GRANTEE)

        assertFalse(GetKeyEntryCurrentModeAuthority.shouldServeGrantCachedResponse(plainAutoGrantee = true))

        assertNotNull(CertificateAliasCache.keys[CertificateAliasCache.Key(UID_OWNER, ALIAS)])

    }



    @Test

    fun grantOwnerAutoGranteeExplicitGenerateMayServeCache() {

        seedGenerated(UID_OWNER)

        registerGrant(UID_OWNER, UID_GRANTEE)

        assertTrue(GetKeyEntryCurrentModeAuthority.shouldServeGrantCachedResponse(plainAutoGrantee = false))

    }



    @Test

    fun sameAliasDifferentUidRemainsIsolated() {

        seedGenerated(UID_OWNER)

        seedPatched(UID_GRANTEE, ALIAS)

        assertNotNull(CertificateAliasCache.findGeneratedKey(UID_OWNER, descriptor(UID_OWNER)))

        assertNotNull(CertificateAliasCache.resolvePatchedResponse(UID_GRANTEE, descriptor(UID_GRANTEE)))

        assertNull(CertificateAliasCache.findGeneratedKey(UID_GRANTEE, descriptor(UID_GRANTEE)))

        assertNull(CertificateAliasCache.resolvePatchedResponse(UID_OWNER, descriptor(UID_OWNER)))

    }



    @Test

    fun grantNamespaceResolvesOwnerKeyForGranteeLookup() {

        seedGenerated(UID_OWNER)

        registerGrant(UID_OWNER, UID_GRANTEE)

        val grantDescriptor =
            KeyDescriptor().apply {
                domain = 0
                nspace = GRANT_NSPACE
            }

        assertNotNull(CertificateAliasCache.findGeneratedKey(UID_GRANTEE, grantDescriptor))

        assertEquals(

            CertificateAliasCache.Key(UID_OWNER, ALIAS),

            CertificateAliasCache.grants[GRANT_NSPACE]?.key,

        )

    }



    // --- stale owner leak guards (11–12) ---



    @Test

    fun staleGeneratedCannotLeakIntoGenuinelyPlainAutoScope() {

        seedGenerated(UID_SHARED)

        assertTrue(UidAggregate.isPlainAuto(listOf(TargetMode.AUTO)))

        GetKeyEntryCurrentModeAuthority.rejectHistoricalOwners(UID_SHARED, descriptor(UID_SHARED))

        assertNull(CertificateAliasCache.findGeneratedKey(UID_SHARED, descriptor(UID_SHARED)))

        assertEquals(

            GetKeyEntryPostPolicy.Action.PASSTHROUGH_UNMODIFIED,

            GetKeyEntryPostPolicy.decide(

                GetKeyEntryPostPolicy.PostHookInput(

                    isPassthroughTracked = false,

                    hasCachedPatch = false,

                    hasGeneratedOwner = false,

                    explicitLeafHack = false,

                    autoPreserveUntrackedReal = true,

                    plainAutoMode = true,

                ),

            ),

        )

    }



    @Test

    fun stalePatchCannotLeakIntoGenuinelyPlainAutoScope() {

        seedPatched(UID_SHARED)

        assertTrue(UidAggregate.isPlainAuto(listOf(TargetMode.AUTO)))

        GetKeyEntryCurrentModeAuthority.rejectHistoricalOwners(UID_SHARED, descriptor(UID_SHARED))

        assertNull(CertificateAliasCache.resolvePatchedResponse(UID_SHARED, descriptor(UID_SHARED)))

        assertEquals(

            GetKeyEntryPostPolicy.Action.PASSTHROUGH_UNMODIFIED,

            GetKeyEntryPostPolicy.decide(

                GetKeyEntryPostPolicy.PostHookInput(

                    isPassthroughTracked = false,

                    hasCachedPatch = false,

                    hasGeneratedOwner = false,

                    explicitLeafHack = false,

                    autoPreserveUntrackedReal = true,

                    plainAutoMode = true,

                ),

            ),

        )

    }

}


