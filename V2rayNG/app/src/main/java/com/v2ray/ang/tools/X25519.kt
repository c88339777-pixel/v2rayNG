package com.v2ray.ang.tools

import java.math.BigInteger
import java.security.SecureRandom

/** Curve25519 key agreement (RFC 7748), used to make WireGuard key pairs. */
object X25519 {

    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24: BigInteger = BigInteger.valueOf(121665)
    private val TWO: BigInteger = BigInteger.valueOf(2)

    private fun decodeLE(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

    private fun encodeLE(n: BigInteger): ByteArray {
        val bigEndian = n.toByteArray()
        val out = ByteArray(32)
        var i = 0
        while (i < 32 && i < bigEndian.size) {
            out[i] = bigEndian[bigEndian.size - 1 - i]
            i++
        }
        return out
    }

    private fun clamp(k: ByteArray): ByteArray {
        val r = k.copyOf()
        r[0] = (r[0].toInt() and 248).toByte()
        r[31] = ((r[31].toInt() and 127) or 64).toByte()
        return r
    }

    fun generatePrivateKey(): ByteArray {
        val raw = ByteArray(32)
        SecureRandom().nextBytes(raw)
        return clamp(raw)
    }

    fun publicKey(privateKey: ByteArray): ByteArray {
        val basePoint = ByteArray(32)
        basePoint[0] = 9
        return scalarMult(privateKey, basePoint)
    }

    fun scalarMult(k: ByteArray, u: ByteArray): ByteArray {
        val scalar = decodeLE(clamp(k))
        val uMasked = u.copyOf()
        uMasked[31] = (uMasked[31].toInt() and 127).toByte()
        val x1 = decodeLE(uMasked).mod(P)

        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val kt = if (scalar.testBit(t)) 1 else 0
            swap = swap xor kt
            if (swap == 1) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }
            swap = kt

            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)

            x3 = da.add(cb).mod(P).pow(2).mod(P)
            z3 = x1.multiply(da.subtract(cb).mod(P).pow(2)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e))).mod(P)
        }

        if (swap == 1) {
            val tx = x2; x2 = x3; x3 = tx
            val tz = z2; z2 = z3; z3 = tz
        }

        val result = x2.multiply(z2.modPow(P.subtract(TWO), P)).mod(P)
        return encodeLE(result)
    }
}
