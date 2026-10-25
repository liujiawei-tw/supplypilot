package com.supplypilot.demo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class DemoCorpusCatalogTrust {
    static final String PINNED_SHA256 = "F34301B7639E4D2FD85579F5525752D0C10CF71236CDEA70B4F1E464D7E2EA9B";
    private static final long MAX_CATALOG_BYTES = 20L * 1024 * 1024;

    private DemoCorpusCatalogTrust() { }

    static byte[] readPinnedCatalog(Path samplesRoot) {
        Path catalog = samplesRoot.resolve("corpus-catalog.json");
        if (!Files.isRegularFile(catalog, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(catalog)) {
            throw new IllegalStateException("TRUSTED_REPOSITORY_CATALOG_MISSING");
        }
        try {
            long size = Files.size(catalog);
            if (size > MAX_CATALOG_BYTES) throw new IllegalStateException("TRUSTED_CATALOG_TOO_LARGE");
            byte[] bytes = Files.readAllBytes(catalog);
            if (!PINNED_SHA256.equals(canonicalSha256(bytes))) {
                throw new IllegalStateException("TRUSTED_CATALOG_HASH_MISMATCH");
            }
            return bytes;
        } catch (IOException exception) {
            throw new IllegalStateException("TRUSTED_REPOSITORY_CATALOG_UNREADABLE", exception);
        }
    }

    private static String canonicalSha256(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            byte[] canonical = text.replace("\r\n", "\n").replace('\r', '\n').getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (java.nio.charset.CharacterCodingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("TRUSTED_CATALOG_HASH_UNAVAILABLE", exception);
        }
    }
}
