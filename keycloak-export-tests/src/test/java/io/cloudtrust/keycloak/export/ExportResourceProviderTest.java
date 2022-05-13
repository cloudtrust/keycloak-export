package io.cloudtrust.keycloak.export;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.cloudtrust.keycloak.test.AbstractInKeycloakTest;
import io.cloudtrust.keycloak.test.container.KeycloakDeploy;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpResponseException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RolesResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import javax.ws.rs.ForbiddenException;
import javax.ws.rs.core.Response;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.hamcrest.CoreMatchers.is;

@ExtendWith(KeycloakDeploy.class)
public class ExportResourceProviderTest extends AbstractInKeycloakTest {
	private static final String KEYCLOAK_URL = "http://localhost:8180";
    private static final String CLIENT = "admin-cli";
    private static final String TEST_USER = "user-test-export";
    private static ClientRepresentation clientBeforeChanges;
    private static final String TEST_REALM_NAME = "test-export";
    private static final String TEST_REALM_PATH = "src/test/resources/" + TEST_REALM_NAME + "-realm.json";

    @BeforeAll
    public static void initRealmAndUsers() throws IOException {
        Keycloak keycloak = Keycloak.getInstance(KEYCLOAK_URL, "master", "admin", "admin", CLIENT);
        clientBeforeChanges = keycloak.realms().realm("master").clients().findByClientId(CLIENT).get(0);
        createTestUser("admin", "admin", "master", TEST_USER, "password", "user");
        //just making sure realm is not already present
        String token = keycloak.tokenManager().getAccessTokenString();
        RealmRepresentation nullRealm = null;
        try {
            nullRealm = exportRealm(token, TEST_REALM_NAME);
        } catch (HttpResponseException e) {
        	Assertions.assertEquals(404, e.getStatusCode());
        }
        Assertions.assertNull(nullRealm);
        //end just making sure realm is not already present
    }

    @AfterAll
    public static void resetRealm() {
        //idempotence
        Keycloak keycloak = Keycloak.getInstance(KEYCLOAK_URL, "master", "admin", "admin", CLIENT);
        UserRepresentation user = keycloak.realm("master").users().search(TEST_USER).get(0);
        keycloak.realm("master").users().delete(user.getId());
        keycloak.realm("master").roles().get("user").remove();
        if (clientBeforeChanges != null) {
            keycloak.realms().realm("master").clients().get(clientBeforeChanges.getId()).update(clientBeforeChanges);
        }
    }

    @Test
    void adminCanExportMasterRealm() throws IOException {
        //TODO activate Full scope Mapping in admin-cli programmatically
        Keycloak keycloak = Keycloak.getInstance(KEYCLOAK_URL, "master", "admin", "admin", CLIENT);
        String token = keycloak.tokenManager().getAccessTokenString();
        RealmRepresentation realmRepresentation = exportRealm(token, "master");
        Assertions.assertNotNull(realmRepresentation);
        Assertions.assertEquals("master", realmRepresentation.getRealm());
        Assertions.assertTrue(realmRepresentation.getUsers().stream().anyMatch(ur -> ur.getUsername().equals("admin")));
        Assertions.assertTrue(realmRepresentation.getClients().size() > 0);
    }

    public interface RunnableEx {
        void run() throws IOException;
    }

    private void withRealm(String realmName, RunnableEx runnable) throws IOException {
        withRealm(realmName, "admin", "admin", runnable);
    }

    private void withRealm(String realmName, String username, String password, RunnableEx runnable) throws IOException {
        Keycloak keycloak = Keycloak.getInstance(KEYCLOAK_URL, "master", username, password, CLIENT);
        try {
            RealmRepresentation realmRepresentation = new ObjectMapper().readValue(
                    getClass().getResourceAsStream("/" + realmName + "-realm.json"), RealmRepresentation.class);
            keycloak.realms().create(realmRepresentation);

            //TestsHelper.importTestRealm(username, password, "/" + realmName + "-realm.json");
            runnable.run();
        } finally {
            try {
                //TestsHelper.deleteRealm("admin", "admin", realmName);
                keycloak.realm(realmName).remove();
            } catch (Exception e) {
                // NOOP
            }
        }
    }

    @Test
    void importEqualsExport() throws IOException {
        Keycloak keycloak = Keycloak.getInstance(KEYCLOAK_URL, "master", "admin", "admin", CLIENT);
        String token = keycloak.tokenManager().getAccessTokenString();
        new ObjectMapper().readTree(new File(TEST_REALM_PATH));
        RealmRepresentation fileRepresentation = new ObjectMapper().readValue(new File(TEST_REALM_PATH), RealmRepresentation.class);
        Assertions.assertNotNull(fileRepresentation);
        withRealm(TEST_REALM_NAME, () -> {
            RealmRepresentation exportedRealm = exportRealm(token, TEST_REALM_NAME);
            Assertions.assertEquals(fileRepresentation.getUsers().size(), exportedRealm.getUsers().size());
            //making sure all users are imported
            IntStream.range(0, fileRepresentation.getUsers().size()).forEach(i -> {
                UserRepresentation fileUser = fileRepresentation.getUsers().get(i);
                UserRepresentation exportedUser = exportedRealm.getUsers().parallelStream().filter(c -> c.getId().equals(fileUser.getId())).findAny().get();
                Assertions.assertEquals(fileUser.getUsername(), exportedUser.getUsername());
                Assertions.assertEquals(fileUser.getCredentials(), exportedUser.getCredentials());
                //making sure credentials are imported
                if (fileUser.getCredentials() != null && !fileUser.getCredentials().isEmpty()) {
                    Assertions.assertEquals(fileUser.getCredentials().get(0).getSecretData(), exportedUser.getCredentials().get(0).getSecretData());
                }
            });
            //making sure client secrets are well imported and exported
            IntStream.range(0, fileRepresentation.getClients().size()).forEach(i -> {
                ClientRepresentation fileClient = fileRepresentation.getClients().get(i);
                ClientRepresentation exportedClient = exportedRealm.getClients().parallelStream().filter(c -> c.getId().equals(fileClient.getId())).findAny().get();
                Assertions.assertEquals(fileClient.getId(), exportedClient.getId());
                Assertions.assertEquals(fileClient.getName(), exportedClient.getName());
                Assertions.assertEquals(fileClient.getSecret(), exportedClient.getSecret());
            });
            //groups...
            IntStream.range(0, fileRepresentation.getGroups().size()).forEach(i -> {
                GroupRepresentation fileGroup = fileRepresentation.getGroups().get(i);
                GroupRepresentation exportedGroup = exportedRealm.getGroups().parallelStream().filter(c -> c.getId().equals(fileGroup.getId())).findAny().get();
                Assertions.assertEquals(fileGroup.getId(), exportedGroup.getId());
                Assertions.assertEquals(fileGroup.getName(), exportedGroup.getName());
            });
            //realm roles (do not compare IDs, as they might be changed by the import mechanism)
            IntStream.range(0, fileRepresentation.getRoles().getRealm().size()).forEach(i -> {
                RoleRepresentation fileRealmRole = fileRepresentation.getRoles().getRealm().get(i);
                Optional<RoleRepresentation> exportRealmRoleOpt = exportedRealm.getRoles().getRealm().parallelStream().filter(c -> c.getName().equals(fileRealmRole.getName())).findAny();
                Assertions.assertTrue(exportRealmRoleOpt.isPresent());
            });
            //clients roles
            fileRepresentation.getRoles().getClient().keySet().forEach(clientId -> {
                List<RoleRepresentation> fileClientRoles = fileRepresentation.getRoles().getClient().get(clientId);
                List<RoleRepresentation> exportedClientRoles = exportedRealm.getRoles().getClient().get(clientId);
                IntStream.range(0, fileClientRoles.size()).forEach(i -> {
                    RoleRepresentation fileClientRole = fileClientRoles.get(i);
                    RoleRepresentation exportedClientRole = exportedClientRoles.parallelStream().filter(c -> c.getId().equals(fileClientRole.getId())).findAny().get();
                    Assertions.assertEquals(fileClientRole.getId(), exportedClientRole.getId());
                    Assertions.assertEquals(fileClientRole.getName(), exportedClientRole.getName());
                });
            });
        });
    }

    @Test
    void nonAdminCantExportMaster() throws IOException {
        Keycloak keycloak = Keycloak.getInstance(KEYCLOAK_URL, "master", TEST_USER, "password", CLIENT);
        String token = keycloak.tokenManager().getAccessTokenString();
        HttpResponseException ex = Assertions.assertThrows(HttpResponseException.class, () -> exportRealm(token, "master"));
        MatcherAssert.assertThat(ex.getStatusCode(), is(403));
    }

    @Test
    void nonMasterAdminCantExportMaster() throws IOException {
        withRealm(TEST_REALM_NAME, () -> {
            final String testAdminUser = "test.admin";
            createTestUser("admin", "admin", TEST_REALM_NAME, testAdminUser, "password", "user", "admin");
            Keycloak keycloak = Keycloak.getInstance(KEYCLOAK_URL, TEST_REALM_NAME, testAdminUser, "password", CLIENT);
            String token = keycloak.tokenManager().getAccessTokenString();
            HttpResponseException ex = Assertions.assertThrows(HttpResponseException.class, () -> exportRealm(token, "master"));
            MatcherAssert.assertThat(ex.getStatusCode(), is(403));
        });
    }

    @Test
    void nonMasterAdminCantExportTestRealm() throws IOException {
        withRealm(TEST_REALM_NAME, () -> {
            final String testAdminUser = "test.admin";
            createTestUser("admin", "admin", TEST_REALM_NAME, testAdminUser, "password", "user", "admin");
            Keycloak keycloak = Keycloak.getInstance(KEYCLOAK_URL, TEST_REALM_NAME, testAdminUser, "password", CLIENT);
            String token = keycloak.tokenManager().getAccessTokenString();
            HttpResponseException ex = Assertions.assertThrows(HttpResponseException.class, () -> exportRealm(token, TEST_REALM_NAME));
            MatcherAssert.assertThat(ex.getStatusCode(), is(403));
        });
    }

    @Test
    void nonAdminCantBuiltInImport() throws IOException {
        RealmRepresentation fileRepresentation = new ObjectMapper().readValue(new File(TEST_REALM_PATH), RealmRepresentation.class);
        Assertions.assertNotNull(fileRepresentation);
        Assertions.assertThrows(ForbiddenException.class, () -> withRealm(TEST_REALM_NAME, TEST_USER, "password", () -> {
        }));
    }

    private static RealmRepresentation exportRealm(String token, String realm) throws IOException {
        try (CloseableHttpClient client = HttpClientBuilder.create().build()) {
            HttpGet get = new HttpGet(KEYCLOAK_URL + "/realms/" + realm + "/export/realm");
            get.addHeader("Authorization", "Bearer " + token);

            HttpResponse response = client.execute(get);
            if (response.getStatusLine().getStatusCode() != 200) {
                throw new HttpResponseException(response.getStatusLine().getStatusCode(), "export failed: " + response.getStatusLine().getStatusCode());
            }
            HttpEntity entity = response.getEntity();
            try (InputStream is = entity.getContent()) {
                ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                return mapper.readValue(is, RealmRepresentation.class);
            }
        }
    }

    private static void createTestUser(String username, String password, String realmName, String newUsername, String newPassword, String... roles) {
        Keycloak keycloak = Keycloak.getInstance(KEYCLOAK_URL, "master", username, password, CLIENT);

        //add roles
        for (String role : roles) {
            RoleRepresentation representation = new RoleRepresentation();
            representation.setName(role);
            RolesResource realmsRoles = keycloak.realms().realm(realmName).roles();
            if (realmsRoles.list().stream().map(RoleRepresentation::getName).noneMatch(role::equals)) {
                realmsRoles.create(representation);
            }
        }

        UserRepresentation userRepresentation = new UserRepresentation();
        userRepresentation.setUsername(newUsername);
        userRepresentation.setEnabled(Boolean.TRUE);
        userRepresentation.setRealmRoles(Arrays.asList(roles));
        Response response = keycloak.realms().realm(realmName).users().create(userRepresentation);
        String location = response.getHeaderString("Location");
        String userId = location.substring(location.lastIndexOf('/')+1);
        response.close();
        CredentialRepresentation rep = new CredentialRepresentation();
        rep.setType(CredentialRepresentation.PASSWORD);
        rep.setValue(newPassword);
        rep.setTemporary(false);
        keycloak.realms().realm(realmName).users().get(userId).resetPassword(rep);
    }
}
