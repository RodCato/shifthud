package com.shifthud.backup

import android.app.Application
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class EncryptedBackupTest {
    private val password="a long portable password Ω".toCharArray()
    @Test fun authenticatedRoundTripAndFreshRandomness() {
        val plain="unicode 🥥 \n exact decimal 35.39".toByteArray()
        val one=BackupCrypto.encrypt(plain,password);val two=BackupCrypto.encrypt(plain,password)
        assertFalse(one.contentEquals(two));assertArrayEquals(plain,BackupCrypto.decrypt(one,password))
        assertFalse(String(one).contains("35.39"))
    }
    @Test fun wrongPasswordAndTamperingRejected() {
        val encrypted=BackupCrypto.encrypt("private payroll".toByteArray(),password)
        assertTrue(runCatching{BackupCrypto.decrypt(encrypted,"different".toCharArray())}.isFailure)
        listOf(28,44,encrypted.lastIndex).forEach{i->val changed=encrypted.copyOf();changed[i]=(changed[i].toInt() xor 1).toByte();assertTrue(runCatching{BackupCrypto.decrypt(changed,password)}.isFailure)}
    }
    @Test fun truncatedAndUnsupportedMetadataRejected() {
        val encrypted=BackupCrypto.encrypt(byteArrayOf(1,2,3),password)
        listOf(0,8,55,encrypted.size-1).forEach{n->assertTrue(runCatching{BackupCrypto.decrypt(encrypted.copyOf(n),password)}.isFailure)}
        val changed=encrypted.copyOf();changed[11]=2
        assertTrue(runCatching{BackupCrypto.decrypt(changed,password)}.isFailure)
        changed[11]=1;changed[23]=1
        assertTrue(runCatching{BackupCrypto.decrypt(changed,password)}.isFailure)
    }
    @Test fun failedWriteCloseAndReadbackNeverReportSuccess() {
        val bytes=byteArrayOf(1,2,3)
        assertTrue(runCatching{writeAndVerify(bytes,{object:java.io.OutputStream(){override fun write(b:Int){throw java.io.IOException("full")}}},{bytes.inputStream()})}.isFailure)
        assertTrue(runCatching{writeAndVerify(bytes,{object:java.io.ByteArrayOutputStream(){override fun close(){throw java.io.IOException("close failed")}}},{bytes.inputStream()})}.isFailure)
        assertTrue(runCatching{writeAndVerify(bytes,{java.io.ByteArrayOutputStream()},{byteArrayOf(1,2).inputStream()})}.isFailure)
        val out=java.io.ByteArrayOutputStream()
        writeAndVerify(bytes,{out},{out.toByteArray().inputStream()})
        assertArrayEquals(bytes,out.toByteArray())
    }
    @Test fun oversizedInputIsBounded() {
        val input=object:java.io.InputStream(){var remaining=BackupCrypto.MAX_BYTES+1;override fun read():Int=if(remaining-->0)0 else -1;override fun read(b:ByteArray,off:Int,len:Int):Int {if(remaining<=0)return -1;val n=minOf(len,remaining);remaining-=n;return n}}
        assertTrue(runCatching{input.readBytesBounded()}.isFailure)
    }
}
