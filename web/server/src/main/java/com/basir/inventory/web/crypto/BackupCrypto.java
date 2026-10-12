package com.basir.inventory.web.crypto;

import org.bouncycastle.crypto.agreement.X25519Agreement;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Backup file formats; a port of shared/Crypto.kt. A web backup (.ajwb) opens in the admin app
 * exactly like a phone backup, and a restore file (.ajwr) made in the admin app for this
 * installation's device code restores here.
 */
public final class BackupCrypto {
    private BackupCrypto() {}

    public static class BackupFormatException extends RuntimeException {
        public BackupFormatException(String message) {
            super(message);
        }
    }

    /** Wraps the per-file content key so this installation alone can reopen its own backups. */
    public interface LocalKeyWrapper {
        byte[] wrap(byte[] contentKey);

        byte[] unwrap(byte[] wrapped);
    }

    // ------------------------------------------------------------------ AEAD helpers

    static final int NONCE = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    public static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    public static byte[] seal(byte[] key, byte[] plain, byte[] aad) {
        try {
            byte[] nonce = randomBytes(NONCE);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return concat(nonce, cipher.doFinal(plain));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] open(byte[] key, byte[] sealed, byte[] aad) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, sealed, 0, NONCE));
        cipher.updateAAD(aad);
        return cipher.doFinal(sealed, NONCE, sealed.length - NONCE);
    }

    static byte[] hkdf(byte[] secret, byte[] salt, String info) {
        HKDFBytesGenerator gen = new HKDFBytesGenerator(new SHA256Digest());
        gen.init(new HKDFParameters(secret, salt, info.getBytes(StandardCharsets.UTF_8)));
        byte[] out = new byte[32];
        gen.generateBytes(out, 0, out.length);
        return out;
    }

    static byte[] gzip(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static byte[] gunzip(byte[] data) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new BackupFormatException("Backup file is damaged");
        }
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    // ------------------------------------------------------------------ backup envelope (.ajwb)

    private static final byte[] BACKUP_MAGIC = "AJWB".getBytes(StandardCharsets.US_ASCII);
    private static final int BACKUP_VERSION = 1;

    /** Layout: "AJWB" | ver u8 | ephemeralPub 32B | providerWrap u16+bytes | localWrap u16+bytes | sealed data */
    public static byte[] encryptBackup(String json, byte[] providerBoxPublic, LocalKeyWrapper local) {
        byte[] contentKey = randomBytes(32);
        X25519PrivateKeyParameters eph = new X25519PrivateKeyParameters(RANDOM);
        byte[] ephPub = eph.generatePublicKey().getEncoded();
        byte[] kek = sharedKey(eph, new X25519PublicKeyParameters(providerBoxPublic, 0), ephPub, providerBoxPublic);
        byte[] providerWrap = seal(kek, contentKey, BACKUP_MAGIC);
        byte[] localWrap = local == null ? new byte[0] : local.wrap(contentKey);

        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.writeBytes(BACKUP_MAGIC);
        header.write(BACKUP_VERSION);
        header.writeBytes(ephPub);
        writeBlock(header, providerWrap);
        writeBlock(header, localWrap);
        byte[] h = header.toByteArray();
        return concat(h, seal(contentKey, gzip(json.getBytes(StandardCharsets.UTF_8)), h));
    }

    public static boolean isBackup(byte[] file) {
        return file.length > 5 && Arrays.equals(Arrays.copyOf(file, 4), BACKUP_MAGIC);
    }

    /** Opens a backup made by this installation. */
    public static String decryptBackupLocally(byte[] file, LocalKeyWrapper local) {
        if (!isBackup(file)) throw new BackupFormatException("Not an Ajwa backup file");
        ByteBuffer buf = ByteBuffer.wrap(file);
        buf.position(4);
        if (buf.get() != BACKUP_VERSION) throw new BackupFormatException("Unsupported backup version");
        byte[] localWrap;
        try {
            buf.position(buf.position() + 32);
            readBlock(buf);
            localWrap = readBlock(buf);
        } catch (RuntimeException e) {
            throw new BackupFormatException("Backup file is damaged");
        }
        if (localWrap.length == 0) throw new BackupFormatException("Backup has no key for this installation");
        byte[] contentKey;
        try {
            contentKey = local.unwrap(localWrap);
        } catch (RuntimeException e) {
            throw new BackupFormatException("Backup was made on a different phone or installation");
        }
        int headerLength = buf.position();
        byte[] header = Arrays.copyOfRange(file, 0, headerLength);
        byte[] body = Arrays.copyOfRange(file, headerLength, file.length);
        try {
            return new String(gunzip(open(contentKey, body, header)), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new BackupFormatException("Backup file is damaged or was modified");
        }
    }

    private static byte[] sharedKey(X25519PrivateKeyParameters priv, X25519PublicKeyParameters pub, byte[] ephPub, byte[] recipientPub) {
        byte[] secret = new byte[32];
        X25519Agreement agreement = new X25519Agreement();
        agreement.init(priv);
        agreement.calculateAgreement(pub, secret, 0);
        return hkdf(secret, concat(ephPub, recipientPub), "AJWB-v1-wrap");
    }

    private static void writeBlock(ByteArrayOutputStream out, byte[] bytes) {
        out.write((bytes.length >> 8) & 0xFF);
        out.write(bytes.length & 0xFF);
        out.writeBytes(bytes);
    }

    private static byte[] readBlock(ByteBuffer buf) {
        int len = buf.getShort() & 0xFFFF;
        byte[] b = new byte[len];
        buf.get(b);
        return b;
    }

    // ------------------------------------------------------------------ restore package (.ajwr)

    private static final byte[] RESTORE_MAGIC = "AJWR".getBytes(StandardCharsets.US_ASCII);

    public static boolean isRestorePackage(byte[] file) {
        return file.length > 5 && Arrays.equals(Arrays.copyOf(file, 4), RESTORE_MAGIC);
    }

    /** Layout: "AJWR" | ver u8 | device 5B | sealed gzip(JSON) u32+bytes | signature 64B */
    public static String openRestorePackage(byte[] file, byte[] device, byte[] signPublic) {
        int size = License.DeviceCode.SIZE;
        if (!isRestorePackage(file) || file.length < 4 + 1 + size + 4 + 64) {
            throw new BackupFormatException("Not an Ajwa restore file");
        }
        byte[] body = Arrays.copyOfRange(file, 0, file.length - 64);
        byte[] sig = Arrays.copyOfRange(file, file.length - 64, file.length);
        Ed25519Signer verifier = new Ed25519Signer();
        verifier.init(false, new Ed25519PublicKeyParameters(signPublic, 0));
        verifier.update(body, 0, body.length);
        if (!verifier.verifySignature(sig)) throw new BackupFormatException("Restore file is not genuine or was modified");
        byte[] fileDevice = Arrays.copyOfRange(body, 5, 5 + size);
        if (!Arrays.equals(fileDevice, device)) throw new BackupFormatException("Restore file was made for a different device code");
        byte[] head = Arrays.copyOfRange(body, 0, 5 + size);
        int len = ByteBuffer.wrap(body, head.length, 4).getInt();
        byte[] sealed = Arrays.copyOfRange(body, head.length + 4, head.length + 4 + len);
        byte[] key = hkdf(device, RESTORE_MAGIC, "AJWR-v1-device");
        try {
            return new String(gunzip(open(key, sealed, head)), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new BackupFormatException("Restore file is damaged");
        }
    }
}
