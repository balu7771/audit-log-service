package com.persistent.auditlog.crypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FieldEncryptorTest {

    private final FieldEncryptor fieldEncryptor = new FieldEncryptor();

    @Test
    void encryptThenDecryptRoundTripsCorrectly() {
        FieldEncryptor.EncryptedField encrypted = fieldEncryptor.encrypt("\"123-45-6789\"");

        String decrypted = fieldEncryptor.decrypt(encrypted.key(), encrypted.iv(), encrypted.ciphertextBase64());

        assertThat(decrypted).isEqualTo("\"123-45-6789\"");
    }

    @Test
    void twoEncryptionsOfSameValueProduceDifferentKeyIvAndCiphertext() {
        FieldEncryptor.EncryptedField first = fieldEncryptor.encrypt("\"same-value\"");
        FieldEncryptor.EncryptedField second = fieldEncryptor.encrypt("\"same-value\"");

        assertThat(first.key()).isNotEqualTo(second.key());
        assertThat(first.iv()).isNotEqualTo(second.iv());
        assertThat(first.ciphertextBase64()).isNotEqualTo(second.ciphertextBase64());
    }

    @Test
    void decryptingWithWrongKeyThrows() {
        FieldEncryptor.EncryptedField encrypted = fieldEncryptor.encrypt("\"secret\"");
        FieldEncryptor.EncryptedField other = fieldEncryptor.encrypt("\"other\"");

        assertThatThrownBy(() -> fieldEncryptor.decrypt(other.key(), encrypted.iv(), encrypted.ciphertextBase64()))
            .isInstanceOf(IllegalStateException.class);
    }
}
