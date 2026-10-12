package com.basir.inventory.web.crypto;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Arrays;

/** License key format; a port of shared/License.kt so keys from the admin app work unchanged. */
public final class License {
    private License() {}

    /** How often backups run; decided by the provider per license key. */
    public enum BackupMode {
        DAILY(1, 1), WEEKLY(2, 7), MONTHLY(3, 30);

        public final int code;
        public final int intervalDays;

        BackupMode(int code, int intervalDays) {
            this.code = code;
            this.intervalDays = intervalDays;
        }

        public static BackupMode fromCode(int code) {
            for (BackupMode m : values()) if (m.code == code) return m;
            return null;
        }
    }

    /** Short identifier for one installation, shown on the activation screen and sent to the provider. */
    public static final class DeviceCode {
        public static final int SIZE = 5;

        private DeviceCode() {}

        public static byte[] fromId(String id) {
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(("AJWA-DEVICE|" + id).getBytes(StandardCharsets.UTF_8));
                return Arrays.copyOf(hash, SIZE);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        /** 5 bytes -> 8 Base32 chars, shown as XXXX-XXXX. */
        public static String display(byte[] code) {
            return Base32.group(Base32.encode(code), 4);
        }
    }

    /**
     * Data carried inside a license key. Days are counted from {@link #EPOCH}.
     * Wire format (14 bytes): version u8 | device 5B | issuedDay u16 | validityDays u8 | backupMode u8 | serial u32
     */
    public record Payload(byte[] device, int issuedDay, int validityDays, BackupMode backupMode, long serial, int version) {
        public static final int CURRENT_VERSION = 1;
        public static final int SIZE = 14;
        public static final LocalDate EPOCH = LocalDate.of(2024, 1, 1);

        public static int dayOf(LocalDate date) {
            return (int) (date.toEpochDay() - EPOCH.toEpochDay());
        }

        public byte[] toBytes() {
            return ByteBuffer.allocate(SIZE)
                    .put((byte) version)
                    .put(device)
                    .putShort((short) issuedDay)
                    .put((byte) validityDays)
                    .put((byte) backupMode.code)
                    .putInt((int) serial)
                    .array();
        }

        public static Payload fromBytes(byte[] bytes) {
            if (bytes.length != SIZE) return null;
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            int version = buf.get() & 0xFF;
            if (version != CURRENT_VERSION) return null;
            byte[] device = new byte[DeviceCode.SIZE];
            buf.get(device);
            int issuedDay = buf.getShort() & 0xFFFF;
            int validity = buf.get() & 0xFF;
            BackupMode mode = BackupMode.fromCode(buf.get() & 0xFF);
            long serial = buf.getInt() & 0xFFFFFFFFL;
            if (mode == null || validity == 0) return null;
            return new Payload(device, issuedDay, validity, mode, serial, version);
        }
    }

    public sealed interface ParseResult {
        record Valid(Payload payload) implements ParseResult {}
        record Malformed() implements ParseResult {}
        record BadSignature() implements ParseResult {}
    }

    private static final int SIG_SIZE = 64;

    /** Used by tests; real keys are signed in the admin app. */
    public static String sign(Payload payload, byte[] signingPrivateKey) {
        byte[] body = payload.toBytes();
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, new Ed25519PrivateKeyParameters(signingPrivateKey, 0));
        signer.update(body, 0, body.length);
        byte[] sig = signer.generateSignature();
        byte[] all = Arrays.copyOf(body, body.length + sig.length);
        System.arraycopy(sig, 0, all, body.length, sig.length);
        return Base32.group(Base32.encode(all), 5);
    }

    public static ParseResult parse(String text, byte[] signingPublicKey) {
        byte[] raw = Base32.decode(text);
        if (raw == null || raw.length != Payload.SIZE + SIG_SIZE) return new ParseResult.Malformed();
        byte[] body = Arrays.copyOfRange(raw, 0, Payload.SIZE);
        byte[] sig = Arrays.copyOfRange(raw, Payload.SIZE, raw.length);
        Ed25519Signer verifier = new Ed25519Signer();
        verifier.init(false, new Ed25519PublicKeyParameters(signingPublicKey, 0));
        verifier.update(body, 0, body.length);
        if (!verifier.verifySignature(sig)) return new ParseResult.BadSignature();
        Payload payload = Payload.fromBytes(body);
        return payload == null ? new ParseResult.Malformed() : new ParseResult.Valid(payload);
    }
}
