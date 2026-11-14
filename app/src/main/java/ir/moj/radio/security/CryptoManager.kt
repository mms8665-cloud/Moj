package ir.moj.radio.security

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class CryptoManager {
    private val random = SecureRandom()
    private val gcmTagLengthBits = 128
    private val ivLengthBytes = 12

    fun deriveNetworkKey(joinCode: String, networkSalt: ByteArray): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(joinCode.toCharArray(), networkSalt, 65536, 256)
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, "AES")
    }

    fun encrypt(plainData: ByteArray, key: SecretKey): ByteArray {
        val iv = ByteArray(ivLengthBytes).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(gcmTagLengthBits, iv))
        val cipherText = cipher.doFinal(plainData)
        return iv + cipherText
    }

    fun decrypt(encryptedPacket: ByteArray, key: SecretKey): ByteArray {
        require(encryptedPacket.size > ivLengthBytes) { "Invalid encrypted packet" }
        val iv = encryptedPacket.copyOfRange(0, ivLengthBytes)
        val cipherText = encryptedPacket.copyOfRange(ivLengthBytes, encryptedPacket.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(gcmTagLengthBits, iv))
        return cipher.doFinal(cipherText)
    }

    fun generateJoinCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // بدون O, 0, I, 1
        return (1..8).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }
}
