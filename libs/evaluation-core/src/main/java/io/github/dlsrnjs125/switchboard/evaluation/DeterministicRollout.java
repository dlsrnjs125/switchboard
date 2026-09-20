package io.github.dlsrnjs125.switchboard.evaluation;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class DeterministicRollout {
    public static final int BUCKET_COUNT = 10_000;

    public int bucket(String targetingKey, String flagKey, String rolloutSeed) {
        byte[] digest = digest(targetingKey, flagKey, rolloutSeed);
        long firstEightBytes = 0L;
        for (int index = 0; index < Long.BYTES; index++) {
            firstEightBytes = (firstEightBytes << 8) | Byte.toUnsignedLong(digest[index]);
        }
        return (int) Long.remainderUnsigned(firstEightBytes, BUCKET_COUNT);
    }

    public byte[] preimage(String targetingKey, String flagKey, String rolloutSeed) {
        Objects.requireNonNull(targetingKey, "targetingKey");
        Objects.requireNonNull(flagKey, "flagKey");
        Objects.requireNonNull(rolloutSeed, "rolloutSeed");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(bytes);
            writeField(data, targetingKey);
            writeField(data, flagKey);
            writeField(data, rolloutSeed);
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory rollout encoding failed", impossible);
        }
    }

    public byte[] digest(String targetingKey, String flagKey, String rolloutSeed) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(preimage(targetingKey, flagKey, rolloutSeed));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }

    public String preimageHex(String targetingKey, String flagKey, String rolloutSeed) {
        return HexFormat.of().formatHex(preimage(targetingKey, flagKey, rolloutSeed));
    }

    public String digestHex(String targetingKey, String flagKey, String rolloutSeed) {
        return HexFormat.of().formatHex(digest(targetingKey, flagKey, rolloutSeed));
    }

    private void writeField(DataOutputStream output, String value) throws IOException {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(utf8.length);
        output.write(utf8);
    }
}
