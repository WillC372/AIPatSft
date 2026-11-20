package com.aquaassistant.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aquaassistant.intelligence.ai.AiProviderProxy;
import com.aquaassistant.support.TestAccounts;
import com.aquaassistant.user.Role;
import com.aquaassistant.user.UserRepository;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * H-019 and H-053: the shared demo administrator (its password is public on the sign-in screen) must not see the real
 * e-mails of the accounts in the AI usage report; it sees the demo and example accounts (@example.com) as they are,
 * and has unlimited AI like any administrator.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DemoAdminPrivacyTest {

    @Autowired private MockMvc mvc;
    @Autowired private TestAccounts accounts;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AiProviderProxy proxy;
    @Autowired private TransactionTemplate tx;

    @Test
    void theDemoAdministratorSeesRealEmailsMaskedAndHasNoAiLimit() throws Exception {
        String person = accounts.register();
        Long personId = accounts.userId(person);
        String email = users.findById(personId).orElseThrow().getEmail();
        jdbc.update("insert into ai_usage (user_id, purpose, model, input_tokens, output_tokens, cached, created_at, success, cost_usd)"
            + " values (?, 'CHAT', 'm', 10, 10, false, ?, true, 0.001)", personId, Timestamp.from(Instant.now()));

        String example = accounts.register();
        Long exampleId = accounts.userId(example);
        String exampleEmail = "ejemplo-" + exampleId + "@example.com";
        jdbc.update("update users set email = ? where id = ?", exampleEmail, exampleId);
        jdbc.update("insert into ai_usage (user_id, purpose, model, input_tokens, output_tokens, cached, created_at, success, cost_usd)"
            + " values (?, 'CHAT', 'm', 10, 10, false, ?, true, 0.001)", exampleId, Timestamp.from(Instant.now()));

        String realAdmin = accounts.register(Role.ADMIN);
        String demoAdmin = accounts.register(Role.ADMIN);
        Long demoId = accounts.userId(demoAdmin);
        tx.executeWithoutResult(s -> users.findById(demoId).ifPresent(u -> { u.markDemo(); users.save(u); }));

        String seenByReal = mvc.perform(get("/api/v1/admin/ai-usage").header("Authorization", "Bearer " + realAdmin))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String seenByDemo = mvc.perform(get("/api/v1/admin/ai-usage").header("Authorization", "Bearer " + demoAdmin))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(seenByReal).contains(email);
        assertThat(seenByDemo).doesNotContain(email).contains(email.charAt(0) + "***" + email.substring(email.indexOf('@')))
            .contains(exampleEmail);

        assertThat(proxy.quotaFor(accounts.userId(realAdmin))).isEqualTo(AiProviderProxy.UNLIMITED);
        assertThat(proxy.quotaFor(demoId)).isEqualTo(AiProviderProxy.UNLIMITED);
    }
}
