package com.ledger;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Stands in for the identity service: a real HTTP JWKS endpoint (so the ledger's JWKS fetching is what gets
 * tested) plus helpers that mint access tokens shaped like the ones OpenIddict issues (RS256, {@code typ: at+jwt},
 * space-separated {@code scope} claim).
 */
public final class TestJwks {

    public static final String ISSUER = "https://identity.test/";
    public static final String AUDIENCE = "ledger-api";
    private static final String KEY_ID = "test-key";

    private static final RSAKey SIGNING_KEY;
    /** Same {@code kid} as the published key but different key material: signatures must not verify. */
    private static final RSAKey IMPOSTOR_KEY;
    private static final HttpServer SERVER;

    static {
        try {
            SIGNING_KEY = new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
            IMPOSTOR_KEY = new RSAKeyGenerator(2048).keyID(KEY_ID).generate();

            byte[] jwks = new JWKSet(SIGNING_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            SERVER = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            SERVER.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(jwks);
                }
            });
            SERVER.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private TestJwks() {
    }

    public static String jwksUri() {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/jwks";
    }

    /** Claims of a valid token for the given scopes; tweak the builder to create invalid variants. */
    public static JWTClaimsSet.Builder claims(String... scopes) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE, "identity-api"))
                .subject("11111111-1111-1111-1111-111111111111")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600)))
                .claim("scope", String.join(" ", scopes));
    }

    /** A valid token carrying exactly these scopes. */
    public static String token(String... scopes) {
        return sign(claims(scopes), SIGNING_KEY, "at+jwt");
    }

    public static String sign(JWTClaimsSet.Builder claims, RSAKey key, String type) {
        try {
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(KEY_ID).type(new JOSEObjectType(type)).build();
            SignedJWT jwt = new SignedJWT(header, claims.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String signWith(JWTClaimsSet.Builder claims, String type) {
        return sign(claims, SIGNING_KEY, type);
    }

    public static String signedByImpostor(JWTClaimsSet.Builder claims) {
        return sign(claims, IMPOSTOR_KEY, "at+jwt");
    }

    /** {@code alg: none}: a token with no signature at all. */
    public static String unsigned(JWTClaimsSet.Builder claims) {
        return new PlainJWT(claims.build()).serialize();
    }

    /** HS256 signed with a shared secret: the classic algorithm-confusion attempt. */
    public static String hs256(JWTClaimsSet.Builder claims) {
        try {
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.HS256)
                    .keyID(KEY_ID).type(new JOSEObjectType("at+jwt")).build();
            SignedJWT jwt = new SignedJWT(header, claims.build());
            jwt.sign(new MACSigner(secret));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
