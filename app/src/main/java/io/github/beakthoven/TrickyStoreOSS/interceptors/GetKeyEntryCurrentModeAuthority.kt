/*
 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.beakthoven.TrickyStoreOSS.interceptors

import android.system.keystore2.KeyDescriptor

/**
 * Phase 6K-FINAL: current target.txt mode wins over historical certificate-response
 * ownership. Plain AUTO must not serve stale SOFTWARE_SYNTHETIC or HYBRID_RE_SIGNED
 * material created under a prior explicit or legacy route.
 */
object GetKeyEntryCurrentModeAuthority {
    const val REJECT_HISTORICAL_GENERATED = "auto-reject-historical-generated-owner"
    const val REJECT_HISTORICAL_PATCHED = "auto-reject-historical-patched-owner"

    fun shouldForwardRealKeystore(plainAutoMode: Boolean): Boolean = plainAutoMode

    fun shouldServeHistoricalOwner(
        plainAutoMode: Boolean,
        hasGeneratedOwner: Boolean,
        hasCachedPatch: Boolean,
    ): Boolean = !plainAutoMode && (hasGeneratedOwner || hasCachedPatch)

    /**
     * Grant-domain pre-hook: plain AUTO grantees must not receive TrickyStore synthetic or
     * patched cache via the grant shortcut; fall through to the real keystore grant read.
     */
    fun shouldServeGrantCachedResponse(plainAutoGrantee: Boolean): Boolean = !plainAutoGrantee

    fun rejectHistoricalOwners(uid: Int, descriptor: KeyDescriptor): Boolean {
        val alias = descriptor.alias ?: return false
        val hadGenerated = CertificateAliasCache.findGeneratedKey(uid, descriptor) != null
        val hadPatched = CertificateAliasCache.resolvePatchedResponse(uid, descriptor) != null
        if (!hadGenerated && !hadPatched) return false
        val reason =
            when {
                hadGenerated -> REJECT_HISTORICAL_GENERATED
                else -> REJECT_HISTORICAL_PATCHED
            }
        CertificateAliasCache.clearSyntheticCertificateState(
            uid,
            alias,
            reason,
            includePersistence = hadGenerated,
        )
        return true
    }
}
