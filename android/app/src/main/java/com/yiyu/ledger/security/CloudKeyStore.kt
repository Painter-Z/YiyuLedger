package com.yiyu.ledger.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CloudKeyStore(context: Context, private val alias: String = "yiyu.personal.cloud.key.v1") {
    private val file = AtomicFile(File(context.noBackupFilesDir, "cloud-key.enc"))
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    @Synchronized private fun wrappingKey(): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    fun hasKey() = file.baseFile.exists()
    @Synchronized fun read(): String? {
        if (!hasKey()) return null
        return try {
            val bytes = file.readFully()
            require(bytes.size > 29 && bytes.size < 8192)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            require(bytes[0] == 1.toByte())
            String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) { throw IllegalStateException("无法读取个人密钥，请在设置中重新添加") }
    }
    @Synchronized fun save(value: String) {
        require(value.length in 10..4096 && value.startsWith("sk-") && value.none { it.isWhitespace() || it.code < 32 }) { "请填写有效的百炼 API Key" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val encrypted = byteArrayOf(1) + cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(encrypted); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    @Synchronized fun clear() { file.delete(); if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias) }
}
