package com.hyde.sfauth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

/**
 * Handles the passkey-free login:
 *   1. Sign a JWT with the private key and exchange it for an access token
 *      (OAuth 2.0 JWT Bearer flow) — server-to-server, no login screen.
 *   2. Exchange that token for a frontdoor URL via the Single Access UI Bridge
 *      API (/services/oauth2/singleaccess). Falls back to the legacy direct
 *      frontdoor.jsp URL if the endpoint is unavailable.
 *
 * The interactive login screen is never rendered, so the passkey / MFA
 * challenge never applies.
 */
public class JWTAuthManager {

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Result of the token exchange. */
    public record Session(String accessToken, String instanceUrl) { }

    /** Convenience: run the whole flow and return a URL the browser can open. */
    public String buildLoginUrl(String redirectPath) throws Exception {
        Session session = authenticate();
        return getFrontdoorUrl(session, redirectPath);
    }

    /** JWT Bearer flow -> access token + instance URL. */
    public Session authenticate() throws Exception {
        RSAPrivateKey key = loadPrivateKey(SalesforceConfig.PRIVATE_KEY_PATH);

        // Signed with the private key; Salesforce verifies with the public cert
        // uploaded to the Connected App. No password/passkey involved.
        String assertion = JWT.create()
                .withIssuer(SalesforceConfig.CONSUMER_KEY)               // iss
                .withSubject(SalesforceConfig.USERNAME)                  // sub
                .withAudience(SalesforceConfig.LOGIN_URL)                // aud
                .withExpiresAt(Instant.now().plusSeconds(180))          // exp (<= 5 min)
                .sign(Algorithm.RSA256(null, key));

        String form = "grant_type=" +
                URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8) +
                "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(SalesforceConfig.LOGIN_URL + "/services/oauth2/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            // invalid_grant -> pre-authorization/permission set missing or bad signature.
            throw new RuntimeException("JWT auth failed [" + resp.statusCode() + "]: " + resp.body());
        }

        JsonNode body = mapper.readTree(resp.body());
        return new Session(body.get("access_token").asText(),
                           body.get("instance_url").asText());
    }

    /**
     * Single Access UI Bridge API -> a fully-formed frontdoor URL.
     * The token must carry a UI-capable scope (web or full) on the Connected App,
     * and instanceUrl must be a My Domain / Experience Cloud host.
     * Falls back to the legacy direct frontdoor.jsp URL if singleaccess fails.
     */
    public String getFrontdoorUrl(Session session, String redirectPath) throws Exception {
        String form = "redirect_uri=" + URLEncoder.encode(redirectPath, StandardCharsets.UTF_8);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(session.instanceUrl() + "/services/oauth2/singleaccess"))
                .header("Authorization", "Bearer " + session.accessToken())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 200) {
            return mapper.readTree(resp.body()).get("frontdoor_uri").asText();
        }

        // Fallback: legacy direct frontdoor.jsp. Works when the token has the web scope.
        System.out.println("singleaccess returned " + resp.statusCode()
                + " -> falling back to direct frontdoor.jsp. Body: " + resp.body());
        String ret = URLEncoder.encode(redirectPath, StandardCharsets.UTF_8);
        return session.instanceUrl() + "/secur/frontdoor.jsp?sid="
                + session.accessToken() + "&retURL=" + ret;
    }

    /** Load a PKCS#8 PEM private key (the "-----BEGIN PRIVATE KEY-----" file). */
    private RSAPrivateKey loadPrivateKey(String path) throws Exception {
        String pem = Files.readString(Path.of(path))
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(pem);
        return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(der));
    }
}
