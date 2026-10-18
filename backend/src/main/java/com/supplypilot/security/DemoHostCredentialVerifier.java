package com.supplypilot.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;

/** Bounded mTLS RPC client; any unavailable or invalid Host result fails closed. */
@Component
@Profile("demo & !prod")
public class DemoHostCredentialVerifier {
    private final URI verifierUri;
    private final Path clientCertificate;
    private final Path clientKey;
    private final Path serverCa;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    public DemoHostCredentialVerifier(
            @Value("${supplypilot.demo.verifier-url:https://host.docker.internal:18443/v1/verify}") String verifierUrl,
            @Value("${supplypilot.demo.verifier-client-cert:/run/supplypilot-demo-verifier/client.crt}") String clientCertificate,
            @Value("${supplypilot.demo.verifier-client-key:/run/supplypilot-demo-verifier/client.key}") String clientKey,
            @Value("${supplypilot.demo.verifier-ca:/run/supplypilot-demo-verifier/ca.crt}") String serverCa
    ) {
        this.verifierUri = URI.create(verifierUrl);
        this.clientCertificate = Path.of(clientCertificate);
        this.clientKey = Path.of(clientKey);
        this.serverCa = Path.of(serverCa);
    }

    public boolean verify(String username, String suppliedPassword) {
        byte[] randomId = new byte[16];
        byte[] randomNonce = new byte[32];
        random.nextBytes(randomId);
        random.nextBytes(randomNonce);
        String requestId = HexFormat.of().formatHex(randomId);
        String nonce = HexFormat.of().formatHex(randomNonce);
        try {
            HttpClient client = HttpClient.newBuilder().sslContext(createTlsContext())
                    .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
            byte[] body = mapper.writeValueAsBytes(new Request(requestId, nonce, username, suppliedPassword));
            HttpRequest request = HttpRequest.newBuilder(verifierUri).timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body().length > 512) return false;
            JsonNode json = mapper.readTree(response.body());
            return json.size() == 3 && requestId.equals(json.path("requestId").asText())
                    && nonce.equals(json.path("nonce").asText()) && json.path("allowed").isBoolean()
                    && json.path("allowed").booleanValue();
        } catch (Exception exception) {
            throw new BadCredentialsException("Demo credential verifier is unavailable", exception);
        } finally {
            java.util.Arrays.fill(randomId, (byte) 0);
            java.util.Arrays.fill(randomNonce, (byte) 0);
        }
    }

    private SSLContext createTlsContext() throws Exception {
        CertificateFactory certificates = CertificateFactory.getInstance("X.509");
        X509Certificate clientCert;
        try (var input = Files.newInputStream(clientCertificate)) {
            clientCert = (X509Certificate) certificates.generateCertificate(input);
        }
        String pem = Files.readString(clientKey, StandardCharsets.US_ASCII)
                .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        var privateKey = KeyFactory.getInstance("RSA").generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        var identity = java.security.KeyStore.getInstance("PKCS12");
        identity.load(null, new char[0]);
        identity.setKeyEntry("host-verifier-client", privateKey, new char[0],
                new java.security.cert.Certificate[]{clientCert});
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(identity, new char[0]);

        X509Certificate ca;
        try (var input = Files.newInputStream(serverCa)) {
            ca = (X509Certificate) certificates.generateCertificate(input);
        }
        var trust = java.security.KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("sp903-host-verifier-ca", ca);
        TrustManagerFactory trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trusts.init(trust);
        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(keys.getKeyManagers(), trusts.getTrustManagers(), null);
        return context;
    }

    private record Request(String requestId, String nonce, String username, String password) { }
}
