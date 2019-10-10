package io.cloudtrust.keycloak.export.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;

public class BetterRealmRepresentationTest {
    @Test
    public void realmDeserializationTest() throws IOException {
        try (InputStream is = BetterRealmRepresentationTest.class.getClassLoader().getResourceAsStream("exported-realm.json")) {
            BetterRealmRepresentation realm = new ObjectMapper().readValue(is, BetterRealmRepresentation.class);
            Assert.assertEquals("Cloudtrust", realm.getId());
            Assert.assertTrue(realm.getRequiredActions().stream().anyMatch(ra -> "NOT_AN_ENUM_VALUE".equals(ra.getAlias())));

            BetterUserRepresentation user = (BetterUserRepresentation) realm.getUsers().get(0);
            Assert.assertEquals("uglykidjoe", user.getUsername());
            Assert.assertEquals(2, user.getRequiredActions().size());
            Assert.assertTrue(user.getRequiredActions().contains("NOT_AN_ENUM_VALUE"));

            BetterCredentialRepresentation cred = (BetterCredentialRepresentation) user.getCredentials().get(0);
            Assert.assertEquals("1b9748f0-b9ba-4aaa-874f-0f0a3652dcf4", cred.getId());
        }
    }
}
