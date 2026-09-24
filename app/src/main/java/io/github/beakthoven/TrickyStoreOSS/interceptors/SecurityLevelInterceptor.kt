/*
 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.beakthoven.TrickyStoreOSS.interceptors

import android.hardware.security.keymint.Algorithm
import android.hardware.security.keymint.KeyParameter
import android.hardware.security.keymint.KeyParameterValue
import android.hardware.security.keymint.KeyPurpose
import android.hardware.security.keymint.Tag
import android.os.IBinder
import android.os.Parcel
import android.security.keymaster.KeymasterDefs
import android.system.keystore2.Authorization
import android.system.keystore2.CreateOperationResponse
import android.system.keystore2.IKeystoreSecurityLevel
import android.system.keystore2.KeyDescriptor
import android.system.keystore2.KeyEntryResponse
import android.system.keystore2.KeyMetadata
import android.system.keystore2.KeyParameters
import android.system.keystore2.ResponseCode
import androidx.annotation.Keep
import io.github.beakthoven.TrickyStoreOSS.AndroidUtils
import io.github.beakthoven.TrickyStoreOSS.CertificateGen
import io.github.beakthoven.TrickyStoreOSS.CertificateHack
import io.github.beakthoven.TrickyStoreOSS.CertificateUtils
import io.github.beakthoven.TrickyStoreOSS.PersistenceManager
import io.github.beakthoven.TrickyStoreOSS.config.PkgConfig
import io.github.beakthoven.TrickyStoreOSS.interceptors.InterceptorUtils.errorReply
import io.github.beakthoven.TrickyStoreOSS.interceptors.InterceptorUtils.getTransactCode
import io.github.beakthoven.TrickyStoreOSS.interceptors.InterceptorUtils.hasException
import io.github.beakthoven.TrickyStoreOSS.interceptors.InterceptorUtils.typedReply
import io.github.beakthoven.TrickyStoreOSS.logging.DiagLog
import io.github.beakthoven.TrickyStoreOSS.logging.Logger
import io.github.beakthoven.TrickyStoreOSS.putCertificateChain
import java.security.KeyPair
import java.security.SecureRandom
import java.security.cert.Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.LockSupport

private const val MAX_ATTESTATION_CHALLENGE_BYTES = 128

private typealias Key = CertificateAliasCache.Key

private typealias Info = CertificateAliasCache.Info

private typealias GrantInfo = CertificateAliasCache.GrantInfo

class SecurityLevelInterceptor(private val original: IKeystoreSecurityLevel, private val level: Int) :
    BinderInterceptor() {
    override val interceptedCodes: IntArray by lazy {
        intArrayOf(generateKeyTransaction, createOperationTransaction, importKeyTransaction)
            .filter { it >= 0 }
            .toIntArray()
    }

    // software forges reply faster than real TEE keygen, which timing probes pick up —
    // pad forged replies to the measured real latency (EMA of forwarded generateKey calls)
    @Volatile private var realKeygenEmaMs: Double? = null
    private val pendingKeygenStarts = ConcurrentHashMap<String, Long>()
    private val pendingDeviceIdForwards = ConcurrentHashMap<Key, Boolean>()
    private val pendingPassthroughKeys = ConcurrentHashMap<Key, Boolean>()

    companion object {
        private const val DEFAULT_KEYGEN_MS = 3.0
        private val generateKeyTransaction = getTransactCode(IKeystoreSecurityLevel.Stub::class.java, "generateKey")
        private val createOperationTransaction =
            getTransactCode(IKeystoreSecurityLevel.Stub::class.java, "createOperation")
        private val importKeyTransaction = getTransactCode(IKeystoreSecurityLevel.Stub::class.java, "importKey")

        private val secureRandom = SecureRandom()

        val keys get() = CertificateAliasCache.keys

        val keyPairs get() = CertificateAliasCache.keyPairs

        val skipLeafHacks get() = CertificateAliasCache.skipLeafHacks

        val keysByNspace get() = CertificateAliasCache.keysByNspace

        val patchedResponses get() = CertificateAliasCache.patchedResponses

        val grants get() = CertificateAliasCache.grants

        val usageRemaining get() = CertificateAliasCache.usageRemaining

        @Keep fun isPassthroughKey(key: Key): Boolean = CertificateAliasCache.isPassthroughKey(key)

        @Keep
        fun isPassthroughDescriptor(uid: Int, descriptor: KeyDescriptor): Boolean =
            CertificateAliasCache.isPassthroughDescriptor(uid, descriptor)

        @Keep
        fun getKeyPairs(uid: Int, alias: String): Pair<KeyPair, List<Certificate>>? =
            CertificateAliasCache.getKeyPairs(uid, alias)

        @Keep fun findAliasForNspace(uid: Int, nspace: Long): String? = CertificateAliasCache.findAliasForNspace(uid, nspace)

        @Keep
        fun findGeneratedKey(uid: Int, descriptor: KeyDescriptor): Info? =
            CertificateAliasCache.findGeneratedKey(uid, descriptor)

        @Keep
        fun resolvePatchedResponse(uid: Int, descriptor: KeyDescriptor): KeyEntryResponse? =
            CertificateAliasCache.resolvePatchedResponse(uid, descriptor)

        @Keep fun isPatchedKey(key: Key): Boolean = CertificateAliasCache.isPatchedKey(key)

        @Keep
        fun shouldSkipLeafHackFor(uid: Int, descriptor: KeyDescriptor): Boolean =
            CertificateAliasCache.shouldSkipLeafHackFor(uid, descriptor)

        @Keep fun resolveKey(uid: Int, descriptor: KeyDescriptor): Key? = CertificateAliasCache.resolveKey(uid, descriptor)

        @Keep
        fun updateKeyCertChain(key: Key, publicCert: ByteArray?, certificateChain: ByteArray?) {
            CertificateAliasCache.updateKeyCertChain(key, publicCert, certificateChain)
        }

        @Keep
        fun clearSyntheticCertificateState(
            uid: Int,
            alias: String,
            reason: String,
            includePersistence: Boolean = false,
        ) = CertificateAliasCache.clearSyntheticCertificateState(uid, alias, reason, includePersistence)

        @Keep
        fun relinquishPassthroughOwnership(uid: Int, alias: String, reason: String) =
            CertificateAliasCache.relinquishPassthroughOwnership(uid, alias, reason)

        @Keep
        fun invalidateCertificateResponseState(
            uid: Int,
            alias: String,
            reason: String,
            removePassthrough: Boolean = true,
        ) = CertificateAliasCache.invalidateCertificateResponseState(uid, alias, reason, removePassthrough)

        @Keep fun cleanupKey(uid: Int, alias: String) = CertificateAliasCache.cleanupKey(uid, alias)

        @Keep fun cleanupAll() = CertificateAliasCache.cleanupAll()
    }

    override fun onPreTransact(
        target: IBinder,
        code: Int,
        flags: Int,
        callingUid: Int,
        callingPid: Int,
        data: Parcel,
    ): Result {
        if (code == importKeyTransaction) {
            return if (PkgConfig.needHack(callingUid) || PkgConfig.needGenerate(callingUid)) Continue else Skip
        }
        if (code == generateKeyTransaction) {
            val startNanos = System.nanoTime()
            Logger.d("intercept key gen uid=$callingUid pid=$callingPid")
            val raw = runCatching {
                data.enforceInterface(IKeystoreSecurityLevel.DESCRIPTOR)
                val keyDescriptor = data.readTypedObject(KeyDescriptor.CREATOR) ?: return@runCatching
                val attestationKeyDescriptor = data.readTypedObject(KeyDescriptor.CREATOR)
                val params = data.createTypedArray(KeyParameter.CREATOR)!!
                val aFlags = data.readInt()
                val entropy = data.createByteArray()
                val kgp = CertificateGen.KeyGenParameters(params)
                val challenge = kgp.attestationChallenge
                if (challenge != null && challenge.size > MAX_ATTESTATION_CHALLENGE_BYTES) {
                    Logger.i(
                        "Rejecting oversized attestation challenge (${challenge.size}B > $MAX_ATTESTATION_CHALLENGE_BYTES) uid=$callingUid alias=${keyDescriptor.alias}"
                    )
                    return errorReply(KeymasterDefs.KM_ERROR_INVALID_INPUT_LENGTH, "Oversized attestation challenge")
                }
                if (keyDescriptor.alias == null) {
                    Logger.d("KeyDescriptor has null alias (KEY_ID domain), passing through to real keystore")
                    return Skip
                }
                if (params.any { it.tag == Tag.CREATION_DATETIME }) {
                    Logger.i("Rejecting caller-supplied CREATION_DATETIME uid=$callingUid alias=${keyDescriptor.alias}")
                    return errorReply(ResponseCode.INVALID_ARGUMENT, "CREATION_DATETIME is auto-injected")
                }
                val hasDeviceIdAttestation = params.any {
                    it.tag == Tag.ATTESTATION_ID_IMEI ||
                        it.tag == Tag.ATTESTATION_ID_MEID ||
                        it.tag == Tag.ATTESTATION_ID_SERIAL ||
                        it.tag == Tag.ATTESTATION_ID_SECOND_IMEI ||
                        it.tag == Tag.DEVICE_UNIQUE_ATTESTATION
                }
                if (
                    hasDeviceIdAttestation &&
                        !PkgConfig.hasPermissionForUid(callingUid, "android.permission.READ_PRIVILEGED_PHONE_STATE")
                ) {
                    Logger.i("Rejecting device ID attestation without READ_PRIVILEGED_PHONE_STATE uid=$callingUid")
                    return errorReply(
                        KeymasterDefs.KM_ERROR_CANNOT_ATTEST_IDS,
                        "Caller lacks READ_PRIVILEGED_PHONE_STATE",
                    )
                }
                val needGenerate = PkgConfig.needGenerate(callingUid)
                val hasAttestKeyPurpose = kgp.purpose.contains(KeyPurpose.ATTEST_KEY)
                val attestationKeyDescriptorSet = attestationKeyDescriptor != null
                val hasAttestationChallenge = challenge != null
                val forceForge =
                    GenerateKeyRoute.computeForceForge(
                        needGenerate = needGenerate,
                        hasDeviceIdAttestation = hasDeviceIdAttestation,
                        hasAttestKeyPurpose = hasAttestKeyPurpose,
                        attestationKeyDescriptorSet = attestationKeyDescriptorSet,
                    )
                when {
                    forceForge -> {
                        val isSymmetric = kgp.algorithm == Algorithm.AES || kgp.algorithm == Algorithm.HMAC
                        if (isSymmetric) {
                            // Symmetric keys are never used for attestation, so forging them
                            // gains nothing and breaks apps whose AES/HMAC operations don't
                            // match the forged key's parameters (e.g. AES-GCM decrypt without
                            // a digest tag -> KM_ERROR_UNSUPPORTED_DIGEST from authorizeOperation).
                            // Forward to the real keystore instead.
                            Logger.d(
                                "generateKey: forwarding symmetric key uid=$callingUid alias=${keyDescriptor.alias}"
                            )
                            logGenerateKeyRoute(
                                callingUid,
                                keyDescriptor.alias,
                                needGenerate,
                                hasDeviceIdAttestation,
                                hasAttestKeyPurpose,
                                attestationKeyDescriptorSet,
                                hasAttestationChallenge,
                                forceForge,
                                "forward-symmetric",
                            )
                            return forwardKeygen(keyDescriptor.alias, startNanos)
                        }
                        val needsForgedAttestation =
                            hasAttestationChallenge ||
                                hasDeviceIdAttestation ||
                                hasAttestKeyPurpose ||
                                attestationKeyDescriptorSet
                        if (!needsForgedAttestation) {
                            // Plain asymmetric keys without any attestation request are
                            // only used for local crypto (e.g. RSA wrapping of stored
                            // secrets). Forging them injects digest/attestation
                            // authorizations the real operation cannot satisfy, which
                            // surfaces as KM_ERROR_UNSUPPORTED_DIGEST (-12) on begin().
                            // Forward to the real keystore instead.
                            Logger.d(
                                "generateKey: forwarding plain asymmetric key uid=$callingUid alias=${keyDescriptor.alias}"
                            )
                            logGenerateKeyRoute(
                                callingUid,
                                keyDescriptor.alias,
                                needGenerate,
                                hasDeviceIdAttestation,
                                hasAttestKeyPurpose,
                                attestationKeyDescriptorSet,
                                hasAttestationChallenge,
                                forceForge,
                                "forward-plain-asymmetric",
                            )
                            return forwardKeygen(keyDescriptor.alias, startNanos)
                        }
                        val needHackForGenerate = PkgConfig.needHack(callingUid)
                        logGenerateKeyRoute(
                            callingUid,
                            keyDescriptor.alias,
                            needGenerate,
                            hasDeviceIdAttestation,
                            hasAttestKeyPurpose,
                            attestationKeyDescriptorSet,
                            hasAttestationChallenge,
                            forceForge,
                            "generate",
                            needHack = needHackForGenerate,
                        )
                        val pair =
                            CertificateGen.generateKeyPair(
                                callingUid,
                                keyDescriptor,
                                attestationKeyDescriptor,
                                kgp,
                                level,
                            ) ?: return@runCatching
                        return storeGeneratedKey(
                            callingUid,
                            keyDescriptor,
                            kgp,
                            pair.first,
                            null,
                            pair.second,
                            needHackForGenerate,
                            startNanos,
                        )
                    }
                    PkgConfig.needHack(callingUid) -> {
                        val selectedRoute =
                            GenerateKeyRoute.selectTopLevelRoute(
                                forceForge = forceForge,
                                needHack = true,
                                explicitLeafHack = PkgConfig.isExplicitLeafHack(callingUid),
                                hasAttestationChallenge = hasAttestationChallenge,
                                needGenerate = needGenerate,
                            )
                        val routeKey = Key(callingUid, keyDescriptor.alias)
                        if (GenerateKeyRoute.isPassthroughRoute(selectedRoute)) {
                            pendingPassthroughKeys[routeKey] = true
                            clearSyntheticCertificateState(
                                callingUid,
                                keyDescriptor.alias,
                                "transition-to-passthrough:pre",
                            )
                            DiagLog.passthroughTrack(
                                "add",
                                callingUid,
                                PassthroughKeyRegistry.aliasHash(keyDescriptor.alias),
                            )
                            DiagLog.certPath(action = "passthrough", reason = "real_tee")
                            Logger.d(
                                "generateKey: passthrough-real-tee uid=$callingUid alias=${keyDescriptor.alias}"
                            )
                        } else {
                            skipLeafHacks.remove(routeKey)
                            Logger.d(
                                "generateKey: forwarding non-attestation key uid=$callingUid alias=${keyDescriptor.alias}"
                            )
                        }
                        if (hasDeviceIdAttestation) {
                            pendingDeviceIdForwards[routeKey] = true
                            DiagLog.deviceIdRoute(
                                callingUid,
                                PkgConfig.diagnosticCachedPackagesForUid(callingUid),
                                selectedRoute,
                            )
                        }
                        logGenerateKeyRoute(
                            callingUid,
                            keyDescriptor.alias,
                            needGenerate,
                            hasDeviceIdAttestation,
                            hasAttestKeyPurpose,
                            attestationKeyDescriptorSet,
                            hasAttestationChallenge,
                            forceForge,
                            selectedRoute,
                            needHack = true,
                        )
                        return forwardKeygen(keyDescriptor.alias, startNanos)
                    }
                    else -> return Skip
                }
            }
            raw.onFailure { Logger.e("parse key gen request", it) }
        }
        if (code == createOperationTransaction) {
            val raw = runCatching {
                data.enforceInterface(IKeystoreSecurityLevel.DESCRIPTOR)
                val descriptor = data.readTypedObject(KeyDescriptor.CREATOR) ?: return@runCatching null
                val info = findGeneratedKey(callingUid, descriptor)
                if (info == null) return@runCatching null
                val opParams = data.createTypedArray(KeyParameter.CREATOR) ?: emptyArray<KeyParameter>()
                // AIDL: createOperation(KeyDescriptor, KeyParameter[], boolean force)
                val force = data.dataAvail() > 0 && data.readBoolean()
                if (force) {
                    Logger.i("createOperation rejected: forced op uid=$callingUid alias=${descriptor.alias}")
                    return@runCatching errorReply(
                        ResponseCode.PERMISSION_DENIED,
                        "Forced operations require system privilege",
                    )
                }
                val key = resolveKey(callingUid, descriptor)
                if (key != null && info.params.usageCountLimit > 0) {
                    val remaining = usageRemaining[key]
                    if (remaining != null && remaining <= 0) {
                        Logger.i("createOperation: usage exhausted uid=$callingUid alias=${descriptor.alias}")
                        return@runCatching errorReply(ResponseCode.KEY_NOT_FOUND, "Key usage limit exhausted")
                    }
                }
                val opRequest = OpRequest.parse(opParams)
                val errCode = authorizeOperation(info.params, opRequest)
                if (errCode != null) {
                    Logger.i(
                        "createOperation rejected for uid=$callingUid alias=${descriptor.alias} nspace=${descriptor.nspace}: KM error $errCode"
                    )
                    return@runCatching errorReply(errCode, "Operation not authorized")
                }
                Logger.d(
                    "createOperation: serving software operation for uid=$callingUid alias=${descriptor.alias} nspace=${descriptor.nspace}"
                )
                val op = SoftwareOperationBinder.create(info, opRequest, resolveKey(callingUid, descriptor))
                val response =
                    CreateOperationResponse().apply {
                        iOperation = op
                        parameters = op.beginParameters?.let { KeyParameters().apply { keyParameter = it } }
                    }
                typedReply(response)
            }
            val result = raw.onFailure { Logger.e("handle createOperation request", it) }.getOrNull()
            if (result != null) return result
        }
        return Skip
    }

    override fun onPostTransact(
        target: IBinder,
        code: Int,
        flags: Int,
        callingUid: Int,
        callingPid: Int,
        data: Parcel,
        reply: Parcel?,
        resultCode: Int,
    ): Result {
        if (code == generateKeyTransaction && reply != null) {
            val raw = runCatching {
                data.enforceInterface(IKeystoreSecurityLevel.DESCRIPTOR)
                val keyDescriptor = data.readTypedObject(KeyDescriptor.CREATOR) ?: return@runCatching null
                val routeKey = Key(callingUid, keyDescriptor.alias)
                val trackDeviceId = pendingDeviceIdForwards.remove(routeKey) == true
                val isPassthrough = pendingPassthroughKeys.remove(routeKey) == true
                val replyStart = reply.dataPosition()
                try {
                    reply.readException()
                } catch (e: Exception) {
                    if (trackDeviceId) {
                        DiagLog.deviceIdRealTeeResult(
                            callingUid,
                            success = false,
                            errorCode = extractBinderErrorCode(e),
                            exceptionClass = e.javaClass.name,
                        )
                    }
                    if (isPassthrough) {
                        DiagLog.passthroughResult(callingUid, success = false, replyUnchanged = true)
                    }
                    return@runCatching null
                }
                reply.setDataPosition(replyStart)
                if (isPassthrough) {
                    if (trackDeviceId) {
                        DiagLog.deviceIdRealTeeResult(
                            callingUid,
                            success = true,
                            errorCode = null,
                            exceptionClass = null,
                        )
                    }
                    reply.readException()
                    val metadata = reply.readTypedObject(KeyMetadata.CREATOR)
                    clearSyntheticCertificateState(
                        callingUid,
                        keyDescriptor.alias,
                        "transition-to-passthrough:post",
                        includePersistence = true,
                    )
                    metadata?.key?.nspace?.let { nspace ->
                        if (nspace != 0L) keysByNspace[nspace] = routeKey
                    }
                    PassthroughKeyRegistry.promote(callingUid, keyDescriptor.alias, metadata?.key?.nspace)
                    DiagLog.passthroughTrack(
                        "promote",
                        callingUid,
                        PassthroughKeyRegistry.aliasHash(keyDescriptor.alias),
                    )
                    recordRealKeygen(keyDescriptor.alias)
                    DiagLog.passthroughResult(callingUid, success = true, replyUnchanged = true)
                    reply.setDataPosition(replyStart)
                    return@runCatching null
                }
                if (trackDeviceId) {
                    DiagLog.deviceIdRealTeeResult(
                        callingUid,
                        success = true,
                        errorCode = null,
                        exceptionClass = null,
                    )
                }
                recordRealKeygen(keyDescriptor.alias)
                reply.readException()
                val metadata = reply.readTypedObject(KeyMetadata.CREATOR) ?: return@runCatching null
                val chain =
                    with(CertificateUtils) {
                        val leaf = metadata.certificate?.toCertificate() ?: return@runCatching null
                        val rest = metadata.certificateChain?.toCertificates() ?: emptyList()
                        (listOf<Certificate>(leaf) + rest).toTypedArray()
                    }
                relinquishPassthroughOwnership(
                    callingUid,
                    keyDescriptor.alias,
                    "transition-to-leaf-forward",
                )
                clearSyntheticCertificateState(
                    callingUid,
                    keyDescriptor.alias,
                    "transition-to-leaf-forward:pre",
                )
                val patched = CertificateHack.hackCertificateChain(chain, callingUid)
                metadata.putCertificateChain(patched).getOrThrow()
                metadata.authorizations = CertificateHack.patchAuthorizations(metadata.authorizations, callingUid)
                val response =
                    KeyEntryResponse().apply {
                        this.metadata = metadata
                        iSecurityLevel = original
                    }
                patchedResponses[Key(callingUid, keyDescriptor.alias)] = response
                metadata.key?.nspace?.let { nspace ->
                    if (nspace != 0L) keysByNspace[nspace] = Key(callingUid, keyDescriptor.alias)
                }
                Logger.i("Patched generateKey chain for uid=$callingUid alias=${keyDescriptor.alias}")
                typedReply(metadata)
            }
            val result = raw.onFailure { Logger.e("patch generateKey reply", it) }.getOrNull()
            if (result != null) return result
        }
        if (code == importKeyTransaction && reply != null && !reply.hasException()) {
            val raw = runCatching {
                data.enforceInterface(IKeystoreSecurityLevel.DESCRIPTOR)
                val keyDescriptor = data.readTypedObject(KeyDescriptor.CREATOR) ?: return@runCatching
                Logger.i(
                    "importKey succeeded, clearing generated state for uid=$callingUid alias=${keyDescriptor.alias}"
                )
                cleanupKey(callingUid, keyDescriptor.alias)
            }
            raw.onFailure { Logger.e("parse importKey request", it) }
        }
        return Skip
    }

    private fun storeGeneratedKey(
        callingUid: Int,
        keyDescriptor: KeyDescriptor,
        kgp: CertificateGen.KeyGenParameters,
        keyPair: KeyPair?,
        secretKey: javax.crypto.SecretKey?,
        chain: List<Certificate>?,
        skipLeafHack: Boolean,
        startNanos: Long,
    ): Result {
        relinquishPassthroughOwnership(callingUid, keyDescriptor.alias, "transition-to-generate")
        clearSyntheticCertificateState(callingUid, keyDescriptor.alias, "transition-to-generate:pre")
        keyDescriptor.nspace = secureRandom.nextLong()
        val key = Key(callingUid, keyDescriptor.alias)
        keysByNspace[keyDescriptor.nspace] = key
        if (keyPair != null && chain != null) keyPairs[key] = Pair(keyPair, chain)
        val response = buildResponse(chain, kgp, keyDescriptor, callingUid)
        keys[key] = Info(keyPair, secretKey, response, kgp)
        if (kgp.usageCountLimit > 0) usageRemaining[key] = kgp.usageCountLimit
        if (skipLeafHack) skipLeafHacks[key] = true
        if (keyPair != null || secretKey != null) {
            PersistenceManager.saveKey(
                callingUid,
                keyDescriptor.alias,
                level,
                skipLeafHack,
                keyDescriptor.nspace,
                keyDescriptor.domain,
                keyPair,
                secretKey,
                chain,
                marshalKeyMetadata(response.metadata),
                kgp,
            )
        }
        padForgedKeygen(startNanos)
        return typedReply(response.metadata)
    }

    private fun forwardKeygen(alias: String?, startNanos: Long): Result {
        alias?.let { pendingKeygenStarts[it] = startNanos }
        return Continue
    }

    private fun recordRealKeygen(alias: String?) {
        val start = alias?.let { pendingKeygenStarts.remove(it) } ?: return
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        if (elapsedMs in 0.3..80.0) realKeygenEmaMs = realKeygenEmaMs?.let { it * 0.75 + elapsedMs * 0.25 } ?: elapsedMs
    }

    private fun padForgedKeygen(startNanos: Long) {
        val targetMs =
            (realKeygenEmaMs ?: DEFAULT_KEYGEN_MS) * (1.0 + (secureRandom.nextGaussian() * 0.06).coerceIn(-0.15, 0.25))
        val remainingNanos = (targetMs * 1_000_000).toLong() - (System.nanoTime() - startNanos)
        if (remainingNanos > 0) LockSupport.parkNanos(remainingNanos)
    }

    private fun buildResponse(
        chain: List<Certificate>?,
        params: CertificateGen.KeyGenParameters,
        descriptor: KeyDescriptor,
        callingUid: Int = 0,
    ): KeyEntryResponse {
        val response = KeyEntryResponse()
        val metadata = KeyMetadata()
        metadata.keySecurityLevel = level
        metadata.modificationTimeMs = System.currentTimeMillis() / 1000
        if (chain != null && chain.isNotEmpty()) {
            metadata.putCertificateChain(chain.toTypedArray()).getOrThrow()
        } else {
            metadata.certificate = null
            metadata.certificateChain = null
        }
        val d = KeyDescriptor()
        d.domain = 4
        d.nspace = descriptor.nspace
        d.alias = null
        metadata.key = d
        val authorizations = ArrayList<Authorization>()
        fun tee(tag: Int, value: KeyParameterValue) = authorizations.add(auth(tag, value, level))
        fun sw(tag: Int, value: KeyParameterValue) =
            authorizations.add(auth(tag, value, 0 /* SoftwareLevel.SOFTWARE */))

        tee(Tag.ALGORITHM, KeyParameterValue.algorithm(params.algorithm))
        for (i in params.purpose) tee(Tag.PURPOSE, KeyParameterValue.keyPurpose(i))
        for (i in params.blockMode) tee(Tag.BLOCK_MODE, KeyParameterValue.blockMode(i))
        for (i in params.digest) tee(Tag.DIGEST, KeyParameterValue.digest(i))
        for (i in params.padding) tee(Tag.PADDING, KeyParameterValue.paddingMode(i))
        for (i in params.mgfDigest) tee(Tag.RSA_OAEP_MGF_DIGEST, KeyParameterValue.digest(i))
        val effectiveKey = CertificateGen.effectiveKeySize(params)
        if (effectiveKey > 0) tee(Tag.KEY_SIZE, KeyParameterValue.integer(effectiveKey))

        if (params.algorithm == Algorithm.EC && params.ecCurve != 0)
            tee(Tag.EC_CURVE, KeyParameterValue.ecCurve(params.ecCurve))
        params.rsaPublicExponent?.let { tee(Tag.RSA_PUBLIC_EXPONENT, KeyParameterValue.longInteger(it.toLong())) }

        if (params.noAuthRequired == true) tee(Tag.NO_AUTH_REQUIRED, KeyParameterValue.boolValue(true))
        tee(Tag.ORIGIN, KeyParameterValue.origin(params.origin ?: 0))
        tee(Tag.OS_VERSION, KeyParameterValue.integer(AndroidUtils.osVersion))

        if (AndroidUtils.patchLevel(callingUid) != AndroidUtils.DO_NOT_REPORT)
            tee(Tag.OS_PATCHLEVEL, KeyParameterValue.integer(AndroidUtils.patchLevel(callingUid)))
        if (AndroidUtils.vendorPatchLevelLong(callingUid) != AndroidUtils.DO_NOT_REPORT)
            tee(Tag.VENDOR_PATCHLEVEL, KeyParameterValue.integer(AndroidUtils.vendorPatchLevelLong(callingUid)))
        if (AndroidUtils.bootPatchLevelLong(callingUid) != AndroidUtils.DO_NOT_REPORT)
            tee(Tag.BOOT_PATCHLEVEL, KeyParameterValue.integer(AndroidUtils.bootPatchLevelLong(callingUid)))

        sw(Tag.CREATION_DATETIME, KeyParameterValue.dateTime(System.currentTimeMillis()))
        params.activeDateTime?.let { sw(Tag.ACTIVE_DATETIME, KeyParameterValue.dateTime(it)) }
        params.originationExpireDateTime?.let { sw(Tag.ORIGINATION_EXPIRE_DATETIME, KeyParameterValue.dateTime(it)) }
        params.usageExpireDateTime?.let { sw(Tag.USAGE_EXPIRE_DATETIME, KeyParameterValue.dateTime(it)) }
        if (params.usageCountLimit > 0) sw(Tag.USAGE_COUNT_LIMIT, KeyParameterValue.integer(params.usageCountLimit))
        params.unlockedDeviceRequired?.let { sw(Tag.UNLOCKED_DEVICE_REQUIRED, KeyParameterValue.boolValue(it)) }
        if (params.callerNonce == true) tee(Tag.CALLER_NONCE, KeyParameterValue.boolValue(true))
        sw(Tag.USER_ID, KeyParameterValue.integer(callingUid / 100000))

        metadata.authorizations = authorizations.toTypedArray<Authorization>()
        response.metadata = metadata
        response.iSecurityLevel = original
        return response
    }

    private fun auth(tag: Int, value: KeyParameterValue, secLevel: Int): Authorization =
        Authorization().apply {
            keyParameter =
                KeyParameter().apply {
                    this.tag = tag
                    this.value = value
                }
            securityLevel = secLevel
        }

    fun loadPersistedKeys() {
        PersistenceManager.loadAll()
            .filter { it.securityLevel == level }
            .forEach { pk ->
                val raw = runCatching {
                    val descriptor =
                        KeyDescriptor().apply {
                            domain = pk.domain
                            nspace = pk.nspace
                            alias = pk.alias
                        }
                    val metadata = pk.metadataBytes?.let { unmarshalKeyMetadata(it) }
                    val response =
                        if (metadata != null) {
                            KeyEntryResponse().apply {
                                this.metadata = metadata
                                iSecurityLevel = original
                            }
                        } else {
                            buildResponse(pk.chain, pk.params, descriptor, pk.uid)
                        }
                    val key = Key(pk.uid, pk.alias)
                    keys[key] = Info(pk.keyPair, pk.secretKey, response, pk.params)
                    if (pk.keyPair != null) keyPairs[key] = Pair(pk.keyPair, pk.chain)
                    if (pk.nspace != 0L) keysByNspace[pk.nspace] = key
                    if (pk.skipLeafHack) skipLeafHacks[key] = true
                    Logger.i("Restored persisted key uid=${pk.uid} alias=${pk.alias}")
                }
                raw.onFailure { Logger.e("Failed to restore persisted key uid=${pk.uid} alias=${pk.alias}", it) }
            }
    }

    private fun marshalKeyMetadata(metadata: KeyMetadata): ByteArray? {
        val raw = runCatching {
            val p = Parcel.obtain()
            try {
                p.writeTypedObject(metadata, 0)
                p.marshall()
            } finally {
                p.recycle()
            }
        }
        return raw.getOrNull()
    }

    private fun unmarshalKeyMetadata(bytes: ByteArray): KeyMetadata? {
        val raw = runCatching {
            val p = Parcel.obtain()
            try {
                p.unmarshall(bytes, 0, bytes.size)
                p.setDataPosition(0)
                p.readTypedObject(KeyMetadata.CREATOR)
            } finally {
                p.recycle()
            }
        }
        return raw.getOrNull()
    }

    private fun extractBinderErrorCode(exception: Throwable): Int? {
        var current: Throwable? = exception
        var depth = 0
        while (current != null && depth < 8) {
            if (current is android.os.ServiceSpecificException) return current.errorCode
            val fields = listOf("errorCode", "error", "status", "code", "kmError", "keymasterError", "responseCode")
            for (fieldName in fields) {
                val code =
                    runCatching {
                        val field = current!!.javaClass.getField(fieldName)
                        field.getInt(current)
                    }.getOrNull()
                if (code != null) return code
            }
            current = current.cause
            depth++
        }
        return null
    }

    private fun logGenerateKeyRoute(
        callingUid: Int,
        alias: String?,
        needGenerate: Boolean,
        hasDeviceIdAttestation: Boolean,
        hasAttestKeyPurpose: Boolean,
        attestationKeyDescriptorSet: Boolean,
        hasAttestationChallenge: Boolean,
        forceForge: Boolean,
        selected: String,
        needHack: Boolean = false,
    ) {
        val packages = PkgConfig.diagnosticCachedPackagesForUid(callingUid)
        val interesting =
            needHack ||
                needGenerate ||
                packages?.any { pkg ->
                    pkg.contains("gms") || pkg.contains("vending") || pkg.contains("google")
                } == true
        if (!interesting) return
        DiagLog.modeInput(
            attestationKeyDescriptorSet = attestationKeyDescriptorSet,
            explicitSynthetic = needGenerate,
            hasAttestKeyPurpose = hasAttestKeyPurpose,
        )
        DiagLog.modeRouting(
            callingUid = callingUid,
            packages = packages,
            alias = alias,
            needHack = needHack,
            needGenerate = needGenerate,
            hasDeviceIdAttestation = hasDeviceIdAttestation,
            hasAttestKeyPurpose = hasAttestKeyPurpose,
            attestationKeyDescriptorSet = attestationKeyDescriptorSet,
            hasAttestationChallenge = hasAttestationChallenge,
            forceForge = forceForge,
            selected = selected,
        )
    }
}
