package com.basir.inventory.web.crypto;

import com.basir.inventory.web.crypto.License.BackupMode;
import com.basir.inventory.web.crypto.License.DeviceCode;
import com.basir.inventory.web.crypto.License.ParseResult;
import com.basir.inventory.web.crypto.License.Payload;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CryptoTest {
    private final Ed25519PrivateKeyParameters signKey = new Ed25519PrivateKeyParameters(new SecureRandom());
    private final byte[] signPublic = signKey.generatePublicKey().getEncoded();

    @Test
    void base32RoundTripIgnoresDashesAndCase() {
        byte[] data = {0, 1, 2, (byte) 250, 99, 127, (byte) 128};
        String text = Base32.encode(data);
        assertArrayEquals(data, Base32.decode(Base32.group(text, 3).toLowerCase()));
        assertNull(Base32.decode("ABC0"));
    }

    @Test
    void deviceCodeMatchesKotlinFormat() {
        String code = DeviceCode.display(DeviceCode.fromId("abc"));
        assertEquals(9, code.length());
        assertEquals('-', code.charAt(4));
    }

    @Test
    void licenseSignAndParse() {
        byte[] device = DeviceCode.fromId("web-test");
        Payload p = new Payload(device, 1000, 30, BackupMode.WEEKLY, 4_000_000_000L, Payload.CURRENT_VERSION);
        String key = License.sign(p, signKey.getEncoded());

        ParseResult r = License.parse(key, signPublic);
        Payload back = assertInstanceOf(ParseResult.Valid.class, r).payload();
        assertArrayEquals(device, back.device());
        assertEquals(1000, back.issuedDay());
        assertEquals(30, back.validityDays());
        assertEquals(BackupMode.WEEKLY, back.backupMode());
        assertEquals(4_000_000_000L, back.serial());

        // Another provider's key, or a changed character, must not verify.
        byte[] otherPublic = new Ed25519PrivateKeyParameters(new SecureRandom()).generatePublicKey().getEncoded();
        assertInstanceOf(ParseResult.BadSignature.class, License.parse(key, otherPublic));
        char c = key.charAt(3) == 'A' ? 'B' : 'A';
        assertInstanceOf(ParseResult.BadSignature.class, License.parse(key.substring(0, 3) + c + key.substring(4), signPublic));
        assertInstanceOf(ParseResult.Malformed.class, License.parse("HELLO", signPublic));
    }

    @Test
    void backupOpensLocallyAndRejectsOtherInstallations() {
        byte[] boxPublic = new org.bouncycastle.crypto.params.X25519PrivateKeyParameters(new SecureRandom()).generatePublicKey().getEncoded();
        byte[] key = BackupCrypto.randomBytes(32);
        BackupCrypto.LocalKeyWrapper local = wrapper(key);
        byte[] file = BackupCrypto.encryptBackup("{\"x\":\"বাংলা\"}", boxPublic, local);

        assertEquals("{\"x\":\"বাংলা\"}", BackupCrypto.decryptBackupLocally(file, local));
        assertThrows(BackupCrypto.BackupFormatException.class,
                () -> BackupCrypto.decryptBackupLocally(file, wrapper(BackupCrypto.randomBytes(32))));
    }

    private static BackupCrypto.LocalKeyWrapper wrapper(byte[] key) {
        return new BackupCrypto.LocalKeyWrapper() {
            public byte[] wrap(byte[] contentKey) {
                return BackupCrypto.seal(key, contentKey, new byte[0]);
            }

            public byte[] unwrap(byte[] wrapped) {
                try {
                    return BackupCrypto.open(key, wrapped, new byte[0]);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }
}
