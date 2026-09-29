package com.ledger.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledger.AbstractIntegrationTest;
import com.ledger.TestJwks;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Two tiers. A normal user acts only on their own accounts (see OwnershipIT). An admin ({@code ledger:admin}, only
 * the identity service's admin role has it) acts on every account, and what they do is attributed to them.
 */
class AdminIT extends AbstractIntegrationTest {

    private static final String[] LEDGER = {"accounts:read", "accounts:write", "transfers:read", "transfers:write"};

    private MockMvc client;
    private String admin;  // identity-service admin: ledger scopes + ledger:admin
    private String user;   // identity-service operator: ledger scopes only
    private UUID usersAccount;

    private static String token(String subject, String name, boolean admin) throws Exception {
        String[] scopes = admin ? append(LEDGER, "ledger:admin") : LEDGER;
        return TestJwks.signWith(TestJwks.claims(scopes).subject(subject).claim("name", name), "at+jwt");
    }

    private static String[] append(String[] a, String s) {
        String[] out = java.util.Arrays.copyOf(a, a.length + 1);
        out[a.length] = s;
        return out;
    }

    private ResultActions as(String token, MockHttpServletRequestBuilder request) throws Exception {
        return client.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    @BeforeEach
    void setUp() throws Exception {
        client = plainMvc();
        admin = token("root-" + UUID.randomUUID(), "Rita Root", true);
        String userSub = "ursula-" + UUID.randomUUID();
        user = token(userSub, "Ursula User", false);
        usersAccount = newAccountOwnedBy("user:" + userSub, AccountType.ASSET);
        fund(usersAccount, AccountType.ASSET, 1_000);
    }

    @Test
    void adminSeesEveryAccountWithItsOwner() throws Exception {
        as(admin, get("/accounts?limit=100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(usersAccount.toString())));
        as(admin, get("/accounts/" + usersAccount))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value(startsWith("user:ursula-")))
                .andExpect(jsonPath("$.balance").value(1_000));
        as(admin, get("/accounts/" + usersAccount + "/statements")).andExpect(status().isOk());
    }

    @Test
    void adminCanNarrowTheListToTheirOwnAccounts() throws Exception {
        String adminSub = "root-mine-" + UUID.randomUUID();
        String me = token(adminSub, "Rita Root", true);
        UUID mine = newAccountOwnedBy("user:" + adminSub, AccountType.ASSET);

        as(me, get("/accounts?limit=100&mine=true"))
                .andExpect(jsonPath("$[*].id", hasItem(mine.toString())))
                .andExpect(jsonPath("$[*].ownerId", everyItem(is("user:" + adminSub))));
    }

    @Test
    void normalUserStillSeesOnlyTheirOwn() throws Exception {
        UUID someoneElses = newAccount(AccountType.ASSET); // owned by the API key
        as(user, get("/accounts?limit=100"))
                .andExpect(jsonPath("$[*].ownerId", everyItem(startsWith("user:ursula-"))));
        as(user, get("/accounts/" + someoneElses)).andExpect(status().isNotFound());
        // ?mine=false does not widen a normal user's view.
        as(user, get("/accounts?limit=100&mine=false"))
                .andExpect(jsonPath("$[*].ownerId", everyItem(startsWith("user:ursula-"))));
    }

    @Test
    void adminCanMoveMoneyOutOfSomeoneElsesAccountAndTheStatementSaysWhoDidIt() throws Exception {
        UUID destination = newAccount(AccountType.ASSET);

        as(admin, post("/transfers").header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":250,"
                                + "\"currency\":\"USD\",\"description\":\"correction\"}")
                                .formatted(usersAccount, destination)))
                .andExpect(status().isCreated());

        assertThat(normalBalance(usersAccount)).isEqualTo(750);
        // The account owner can see that an admin, by name, moved their money.
        as(user, get("/accounts/" + usersAccount + "/statements"))
                .andExpect(jsonPath("$.entries[-1:].initiatedBy", hasItem("Rita Root")))
                .andExpect(jsonPath("$.entries[-1:].description", hasItem("correction")));
    }

    @Test
    void normalUserTransfersAreAttributedToThemselves() throws Exception {
        UUID destination = newAccount(AccountType.ASSET);
        as(user, post("/transfers").header("Idempotency-Key", newKey()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":10,\"currency\":\"USD\"}"
                                .formatted(usersAccount, destination)))
                .andExpect(status().isCreated());
        as(user, get("/accounts/" + usersAccount + "/statements"))
                .andExpect(jsonPath("$.entries[-1:].initiatedBy", hasItem("Ursula User")));
    }

    @Test
    void adminCanCloseAndDeleteSomeoneElsesAccount() throws Exception {
        UUID empty = newAccountOwnedBy("user:someone-" + UUID.randomUUID(), AccountType.ASSET);
        UUID unused = newAccountOwnedBy("user:someone-" + UUID.randomUUID(), AccountType.ASSET);

        as(admin, delete("/accounts/" + empty)).andExpect(status().isNoContent());
        as(admin, delete("/accounts/" + unused + "?permanent=true")).andExpect(status().isNoContent());

        as(admin, get("/accounts/" + empty)).andExpect(jsonPath("$.closedAt").exists());
        as(admin, get("/accounts/" + unused)).andExpect(status().isNotFound());
    }

    @Test
    void evenAnAdminCannotEraseHistoryOrStrandMoney() throws Exception {
        // The ledger's invariants are not permissions: they bind admins too.
        as(admin, delete("/accounts/" + usersAccount + "?permanent=true"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ledger:problem:account-has-history"));
        as(admin, delete("/accounts/" + usersAccount))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ledger:problem:account-not-empty"));
    }

    @Test
    void theAdminScopeIsWhatMatters() throws Exception {
        // Same person, same name, but a token without ledger:admin: back to normal-user rules.
        String sub = "root-" + UUID.randomUUID();
        String withoutAdminScope = token(sub, "Rita Root", false);
        as(withoutAdminScope, get("/accounts/" + usersAccount)).andExpect(status().isNotFound());
    }

    @Test
    void newAccountsRecordAReadableOwnerName() throws Exception {
        as(user, post("/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Named\",\"currency\":\"USD\",\"type\":\"ASSET\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerName").value("Ursula User"));
    }
}
