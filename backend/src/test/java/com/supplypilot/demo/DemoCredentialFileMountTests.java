package com.supplypilot.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.BindMode;

/** Exercises the product's directory bind mount with disposable password-only test credentials. */
@Testcontainers(disabledWithoutDocker = true)
class DemoCredentialFileMountTests {
    @TempDir Path tempDir;

    @Test
    void hostSynchronizerPublishesAtomicEnvironmentChangesWithoutRestart() throws Exception {
        Path environmentFile = tempDir.resolve(".env.demo.test");
        Path credentialDirectory = tempDir.resolve("published");
        Path script = Path.of(System.getProperty("user.dir"), "..", "scripts",
                "sync-sp903-demo-credential.py").toAbsolutePath().normalize();
        String python = System.getenv().getOrDefault("PYTHON", System.getProperty("os.name").startsWith("Windows")
                ? "python" : "python3");
        Files.writeString(environmentFile, "DB_PASSWORD=fixture-only\nDEMO_ACCOUNT_PASSWORD=MountCheckOne\n");
        Files.createDirectories(credentialDirectory);
        Process watcher = null;
        try (GenericContainer<?> reader = new GenericContainer<>(DockerImageName.parse("mysql:8.4.11"))
                .withFileSystemBind(credentialDirectory.toString(), "/run/supplypilot-demo-credentials", BindMode.READ_ONLY)
                .withCommand("sh", "-c", "while true; do sleep 1; done")) {
            reader.start();
            String containerIdBeforeRotation = reader.getContainerId();
            runSync(python, script, environmentFile, credentialDirectory);
            assertPasswordVisible(reader, "MountCheckOne");

            watcher = startSync(python, script, environmentFile, credentialDirectory, containerIdBeforeRotation);
            Files.writeString(environmentFile, "DEMO_ACCOUNT_PASSWORD=MountCheckTwo\n");
            awaitPassword(reader, "MountCheckTwo");

            Path replacement = tempDir.resolve(".env.demo.replacement");
            Files.writeString(replacement, "DEMO_ACCOUNT_PASSWORD=MountCheckThree\n");
            Files.move(replacement, environmentFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            awaitPassword(reader, "MountCheckThree");
            assertEquals(containerIdBeforeRotation, reader.getContainerId(),
                    "Credential rotation must not restart or recreate Backend");
            assertEquals(true, watcher.isAlive(), "Host synchronizer should remain active while Backend runs");
        } finally {
            if (watcher != null) {
                if (!watcher.waitFor(10, TimeUnit.SECONDS)) {
                    watcher.destroyForcibly();
                    watcher.waitFor(5, TimeUnit.SECONDS);
                }
            }
        }
    }

    private Process startSync(String python, Path script, Path environmentFile, Path credentialDirectory,
                              String backendContainer) throws Exception {
        ProcessBuilder builder = syncCommand(python, script, environmentFile, credentialDirectory);
        builder.command().add("--watch");
        builder.command().add("--backend-container");
        builder.command().add(backendContainer);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        return builder.start();
    }

    private void runSync(String python, Path script, Path environmentFile, Path credentialDirectory) throws Exception {
        ProcessBuilder builder = syncCommand(python, script, environmentFile, credentialDirectory);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Initial host credential synchronization timed out");
        }
        assertEquals(0, process.exitValue(), "Initial host credential synchronization failed");
    }

    private ProcessBuilder syncCommand(String python, Path script, Path environmentFile, Path credentialDirectory) {
        return new ProcessBuilder(python, script.toString(), "--environment-file", environmentFile.toString(),
                "--credential-directory", credentialDirectory.toString());
    }

    private void awaitPassword(GenericContainer<?> reader, String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            var result = reader.execInContainer("cat", "/run/supplypilot-demo-credentials/password");
            if (result.getExitCode() == 0 && expected.equals(result.getStdout())) {
                assertEquals(true, reader.isRunning(), "Credential update unexpectedly stopped Backend");
                return;
            }
            Thread.sleep(50);
        }
        assertPasswordVisible(reader, expected);
    }

    private void assertPasswordVisible(GenericContainer<?> reader, String expected) throws Exception {
        var result = reader.execInContainer("cat", "/run/supplypilot-demo-credentials/password");
        assertEquals(0, result.getExitCode(), "The synchronized credential file was not readable");
        assertEquals(expected, result.getStdout(),
                "The directory bind mount did not expose the replacement without container restart");
    }
}
