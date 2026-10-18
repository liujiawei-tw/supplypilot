package com.supplypilot.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.BindMode;

/** Exercises Docker Desktop's Windows host-file bind mount behavior with disposable test credentials. */
@Testcontainers(disabledWithoutDocker = true)
class DemoCredentialFileMountTests {
    @TempDir Path tempDir;

    @Test
    void seesInPlaceWritesAndAtomicReplacementWithoutRestart() throws Exception {
        Path passwordFile = tempDir.resolve(".env.demo.test");
        Files.writeString(passwordFile, "DEMO_ACCOUNT_PASSWORD=MountCheckOne\n", StandardCharsets.UTF_8);
        try (GenericContainer<?> reader = new GenericContainer<>(DockerImageName.parse("mysql:8.4.11"))
                .withFileSystemBind(passwordFile.toString(), "/run/demo.env", BindMode.READ_ONLY)
                .withCommand("sh", "-c", "while true; do sleep 1; done")) {
            reader.start();
            assertPasswordVisible(reader, "MountCheckOne");

            Files.writeString(passwordFile, "DEMO_ACCOUNT_PASSWORD=MountCheckTwo\n", StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            assertPasswordVisible(reader, "MountCheckTwo");

            Path replacement = tempDir.resolve(".env.demo.replacement");
            Files.writeString(replacement, "DEMO_ACCOUNT_PASSWORD=MountCheckThree\n", StandardCharsets.UTF_8);
            Files.move(replacement, passwordFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            assertPasswordVisible(reader, "MountCheckThree");
        }
    }

    private void assertPasswordVisible(GenericContainer<?> reader, String expected) throws Exception {
        var result = reader.execInContainer("sh", "-c",
                "grep -q '^DEMO_ACCOUNT_PASSWORD=" + expected + "$' /run/demo.env");
        assertEquals(0, result.getExitCode(), "The mounted credential file was not refreshed without restart");
    }
}
