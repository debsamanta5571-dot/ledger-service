package com.ledger;

import com.ledger.account.AccountType;
import com.ledger.ledger.Direction;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One Postgres container shared by every integration test class in the JVM (singleton pattern),
 * so the cached Spring context never points at a stopped container. Ryuk cleans it up on exit.
 * Flyway migrates it when the context first starts.
 *
 * <p>The autowired {@code MockMvc} sends the bootstrap API key on every request; use {@link #plainMvc()}
 * for tests that need full control over headers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestMockMvcConfig.class)
// Test keys may write. A plain @TestPropertySource has LOWER precedence than @DynamicPropertySource, so a
// subclass can still override the scopes dynamically (see ApiKeyScopeIT).
@TestPropertySource(properties = "ledger.auth.api-key-scopes=accounts:read,accounts:write,transfers:read,transfers:write")
public abstract class AbstractIntegrationTest {

    public static final String API_KEY = "test-bootstrap-key";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("ledger.auth.bootstrap-api-key", () -> API_KEY);
        registry.add("ledger.auth.bootstrap-rate-limit-per-minute", () -> "1000000");
        // The default API key is read-only; the general tests need it to write, JwtScopeIT/ApiKeyScopeIT test the rest.
        registry.add("ledger.auth.jwt.jwk-set-uri", TestJwks::jwksUri);
        registry.add("ledger.auth.jwt.issuer", () -> TestJwks.ISSUER);
        registry.add("ledger.auth.jwt.audience", () -> TestJwks.AUDIENCE);
        registry.add("ledger.jobs.daily-balances.enabled", () -> "false");
    }

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected MockMvc mvc;
    @Autowired private WebApplicationContext webContext;

    /** MockMvc with the full security chain but no default headers. */
    protected MockMvc plainMvc() {
        return MockMvcBuilders.webAppContextSetup(webContext).apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    /** Owner id of the bootstrap API key, i.e. the caller behind the autowired {@code mvc}. */
    protected String apiKeyOwner() {
        return jdbc.queryForObject("SELECT id::text FROM api_keys WHERE name = 'bootstrap' AND active", String.class);
    }

    /** Creates an account owned by the default test caller (the bootstrap API key). */
    protected UUID newAccount(AccountType type, String currency, long overdraftLimit) {
        return newAccountOwnedBy(apiKeyOwner(), type, currency, overdraftLimit);
    }

    protected UUID newAccountOwnedBy(String owner, AccountType type, String currency, long overdraftLimit) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO accounts (id, owner_id, name, currency, type, overdraft_limit) VALUES (?, ?, ?, ?, ?, ?)
                """, id, owner, "test-" + id, currency, type.name(), overdraftLimit);
        return id;
    }

    protected UUID newAccountOwnedBy(String owner, AccountType type) {
        return newAccountOwnedBy(owner, type, "USD", 0);
    }

    protected UUID newAccount(AccountType type) {
        return newAccount(type, "USD", 0);
    }

    /**
     * Gives {@code account} a balance of {@code amount} by posting a balanced transaction against a throwaway
     * counter-account of the opposite type (bypassing the API, which cannot create money from nothing).
     */
    protected void fund(UUID account, AccountType type, long amount) {
        String currency = jdbc.queryForObject("SELECT currency FROM accounts WHERE id = ?", String.class, account);
        AccountType counterType = type == AccountType.ASSET ? AccountType.LIABILITY : AccountType.ASSET;
        UUID counter = newAccount(counterType, currency, 0);
        Direction accountSide = type.increaseSide();
        Direction counterSide = accountSide == Direction.DEBIT ? Direction.CREDIT : Direction.DEBIT;
        posting(account, accountSide, counter, counterSide, amount, null);
    }

    /** Inserts one balanced two-entry transaction, optionally at an explicit time (null = now). */
    protected UUID posting(UUID accountA, Direction sideA, UUID accountB, Direction sideB, long amount,
                           java.time.Instant at) {
        UUID tx = UUID.randomUUID();
        jdbc.update("INSERT INTO transactions (id, description) VALUES (?, ?)", tx, "seed");
        insertEntry(tx, accountA, sideA, amount, at);
        insertEntry(tx, accountB, sideB, amount, at);
        return tx;
    }

    private void insertEntry(UUID tx, UUID account, Direction side, long amount, java.time.Instant at) {
        if (at == null) {
            jdbc.update("INSERT INTO entries (transaction_id, account_id, direction, amount) VALUES (?, ?, ?, ?)",
                    tx, account, side.name(), amount);
        } else {
            jdbc.update("INSERT INTO entries (transaction_id, account_id, direction, amount, created_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    tx, account, side.name(), amount, java.sql.Timestamp.from(at));
        }
    }

    protected org.springframework.test.web.servlet.ResultActions postTransfer(
            MockMvc client, String key, UUID from, UUID to, long amount, String currency) throws Exception {
        String body = """
                {"fromAccountId":"%s","toAccountId":"%s","amount":%d,"currency":"%s","description":"test transfer"}"""
                .formatted(from, to, amount, currency);
        return client.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/transfers")
                .header("Idempotency-Key", key)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(body));
    }

    protected org.springframework.test.web.servlet.ResultActions postTransfer(String key, UUID from, UUID to,
                                                                             long amount) throws Exception {
        return postTransfer(mvc, key, from, to, amount, "USD");
    }

    protected static String newKey() {
        return "key-" + UUID.randomUUID();
    }

    protected long normalBalance(UUID account) {
        AccountType type = AccountType.valueOf(
                jdbc.queryForObject("SELECT type FROM accounts WHERE id = ?", String.class, account));
        Long net = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END), 0)
                FROM entries WHERE account_id = ?
                """, Long.class, account);
        return type.balanceFrom(net);
    }

    protected long entryCount(UUID account) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM entries WHERE account_id = ?", Long.class, account);
    }
}
