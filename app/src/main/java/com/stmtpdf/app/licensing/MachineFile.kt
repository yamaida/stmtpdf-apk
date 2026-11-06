package com.stmtpdf.app.licensing

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 生成与官网兼容的机器码文件（.stmkey）。
 * 格式：{"v":"STMTPDF-ENV1","kind":"request","blob": base64url(RSA(AES key) | nonce | ciphertext | tag)}
 * 服务器持有 RSA 私钥，App 只内置公钥。
 */
object MachineFile {

    fun build(context: Context): File {
        val publicPem = context.assets.open("keys/machine_rsa_public.pem").readBytes()
        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(pemToDer(publicPem)))

        val aesKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val wrapped = cipher.doFinal(aesKey)

        val payload = JSONObject()
            .put("machine_id", MachineCode.formatted(context))
            .put("fp", MachineCode.raw(context))
            .put("platform", "android")
            .put("created", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()))
            .toString()

        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val aes = Cipher.getInstance("AES/GCM/NoPadding")
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, nonce))
        val cipherText = aes.doFinal(payload.toByteArray(Charsets.UTF_8))
        val tag = cipherText.copyOfRange(cipherText.size - 16, cipherText.size)
        val body = cipherText.copyOfRange(0, cipherText.size - 16)

        val blob = wrapped + nonce + body + tag
        val encoded = Base64.encodeToString(blob, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        val json = JSONObject()
            .put("v", "STMTPDF-ENV1")
            .put("kind", "request")
            .put("blob", encoded)
            .toString()

        val dir = File(context.filesDir, "machine").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val file = File(dir, "StmtPDF-Machine-$stamp.stmkey")
        file.writeText(json)
        return file
    }

    private fun pemToDer(pem: ByteArray): ByteArray {
        val text = String(pem, Charsets.UTF_8)
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")
        return Base64.decode(text, Base64.DEFAULT)
    }
}
