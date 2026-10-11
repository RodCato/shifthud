package com.shifthud.backup

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** v1: magic(8), format(4), Argon version/memory/iterations/lanes(4 each), salt(16), nonce(12), GCM payload+tag. */
object BackupCrypto {
    const val MAX_BYTES=32*1024*1024
    private val magic="SHIFTHUD".toByteArray(Charsets.US_ASCII)
    private const val HEADER=56
    fun encrypt(plain:ByteArray,password:CharArray):ByteArray {
        require(plain.size<=MAX_BYTES-HEADER-16){"Dataset exceeds the 32 MiB backup limit."}
        val salt=ByteArray(16);val nonce=ByteArray(12);SecureRandom().apply{nextBytes(salt);nextBytes(nonce)}
        val header=ByteBuffer.allocate(HEADER).put(magic).putInt(1).putInt(19).putInt(65536).putInt(3).putInt(4).put(salt).put(nonce).array()
        return header+crypt(Cipher.ENCRYPT_MODE,plain,password,header)
    }
    fun decrypt(file:ByteArray,password:CharArray):ByteArray {
        require(file.size in HEADER+16..MAX_BYTES){"Truncated or oversized backup."}
        val header=file.copyOfRange(0,HEADER);val b=ByteBuffer.wrap(header)
        val found=ByteArray(8);b.get(found)
        require(found.contentEquals(magic)){"Not a ShiftHUD backup."}
        require(b.int==1){"Unsupported backup format. Update ShiftHUD; no data was changed."}
        require(b.int==19 && b.int==65536 && b.int==3 && b.int==4){"Unsupported encryption parameters."}
        return try {crypt(Cipher.DECRYPT_MODE,file.copyOfRange(HEADER,file.size),password,header)}
        catch(e:javax.crypto.AEADBadTagException){throw IllegalArgumentException("Incorrect password or damaged backup. Nothing was changed.",e)}
    }
    private fun crypt(mode:Int,input:ByteArray,password:CharArray,header:ByteArray):ByteArray {
        val salt=header.copyOfRange(28,44);val nonce=header.copyOfRange(44,56)
        val parameters=Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(19).withMemoryAsKB(65536).withIterations(3).withParallelism(4).withSalt(salt).build()
        val key=ByteArray(32)
        try {
            Argon2BytesGenerator().apply{init(parameters);generateBytes(password,key)}
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(mode,SecretKeySpec(key,"AES"),GCMParameterSpec(128,nonce));updateAAD(header);doFinal(input)
            }
        } finally {key.fill(0);parameters.clear()}
    }
}
