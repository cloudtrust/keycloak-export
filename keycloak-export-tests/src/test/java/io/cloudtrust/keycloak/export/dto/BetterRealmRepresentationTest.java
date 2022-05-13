package io.cloudtrust.keycloak.export.dto;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

class BetterRealmRepresentationTest {
    @Test
    void realmDeserializationTest() throws IOException {
        try (InputStream is = BetterRealmRepresentationTest.class.getClassLoader().getResourceAsStream("exported-realm.json")) {
            BetterRealmRepresentation realm = new ObjectMapper().readValue(is, BetterRealmRepresentation.class);
            Assertions.assertEquals("Cloudtrust", realm.getId());
            Assertions.assertTrue(realm.getRequiredActions().stream().anyMatch(ra -> "NOT_AN_ENUM_VALUE".equals(ra.getAlias())));

            BetterUserRepresentation user = (BetterUserRepresentation) realm.getUsers().get(0);
            Assertions.assertEquals("uglykidjoe", user.getUsername());
            Assertions.assertEquals(2, user.getRequiredActions().size());
            Assertions.assertTrue(user.getRequiredActions().contains("NOT_AN_ENUM_VALUE"));

            BetterCredentialRepresentation cred = (BetterCredentialRepresentation) user.getCredentials().get(0);
            Assertions.assertEquals("1b9748f0-b9ba-4aaa-874f-0f0a3652dcf4", cred.getId());
        }
    }
}
