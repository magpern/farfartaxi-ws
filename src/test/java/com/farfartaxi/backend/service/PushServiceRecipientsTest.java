package com.farfartaxi.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.farfartaxi.backend.model.PushSubscriptionEntity;
import com.farfartaxi.backend.model.Role;
import com.farfartaxi.backend.model.UserEntity;
import com.farfartaxi.backend.repo.PushSubscriptionRepository;
import com.farfartaxi.backend.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:farfartaxi_push;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false",
    "app.jwt.secret=test-only-integration-secret-key-012345678901234567890123",
    "app.admin.email=admin@test.local",
    "app.admin.password=Admin123!Test",
    "app.admin.name=Admin Test"
})
class PushServiceRecipientsTest {
    @Autowired private PushService pushService;
    @Autowired private UserRepository userRepository;
    @Autowired private PushSubscriptionRepository subscriptions;

    @Test
    void notifyRoleSelectsOnlySameWorldSubscribers() {
        Long real = subscribe("push-real-driver@test.local", false);
        Long test = subscribe("push-test-driver@test.local", true);

        assertThat(pushService.recipientsFor(Role.DRIVER, false)).extracting(s -> s.getUser().getId())
            .contains(real).doesNotContain(test);
        assertThat(pushService.recipientsFor(Role.DRIVER, true)).extracting(s -> s.getUser().getId())
            .contains(test).doesNotContain(real);
    }

    private Long subscribe(String email, boolean isTest) {
        UserEntity u = new UserEntity();
        u.setEmail(email);
        u.setFullName(email);
        u.setRole(Role.DRIVER);
        u.setTest(isTest);
        u.setEnabled(true);
        u.setApproved(true);
        u.setMustChangePassword(false);
        u = userRepository.save(u);
        PushSubscriptionEntity s = new PushSubscriptionEntity();
        s.setUser(u);
        s.setEndpoint("https://push.invalid/" + email);
        s.setP256dh("p");
        s.setAuth("a");
        subscriptions.save(s);
        return u.getId();
    }
}
