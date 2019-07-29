package io.cloudtrust.keycloak.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpResponseException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.logging.Logger;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.*;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.*;
import org.keycloak.services.resource.RealmResourceProviderFactory;
import org.keycloak.test.FluentTestsHelper;
import org.keycloak.test.TestsHelper;

import javax.ws.rs.ForbiddenException;
import javax.ws.rs.core.Response;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.hasProperty;

@RunWith(Arquillian.class)
@RunAsClient
public class ExportResourceProviderTest {

    protected static final Logger logger = Logger.getLogger(ExportResourceProviderTest.class);

    private static final String KEYCLOAK_URL = getKeycloakUrl();
    private static final String CLIENT = "admin-cli";
    private static final String TEST_USER = "user-test-export";
    private static ClientRepresentation clientBeforeChanges;
    private static final String TEST_REALM_NAME = "test-export";
    private static final String TEST_REALM_PATH = "src/test/resources/" + TEST_REALM_NAME + "-realm.json";

    @Rule
    public ExpectedException expectedEx = ExpectedException.none();

    private static String getKeycloakUrl() {
        String url = FluentTestsHelper.DEFAULT_KEYCLOAK_URL;
        try {
            URI uri = new URI(FluentTestsHelper.DEFAULT_KEYCLOAK_URL);
            url = url.replace(String.valueOf(uri.getPort()), System.getProperty("auth.server.http.port", "8080"));
        } catch (Exception e) {
            // Ignore
        }
        return url;
    }

    @BeforeClass
    public static void initRealmAndUsers() throws IOException {
        Keycloak keycloak = Keycloak.getInstance(KEYCLOAK_URL, "master", "admin", "admin", CLIENT);
        clientBeforeChanges = keycloak.realms().realm("master").clients().findByClientId(CLIENT).get(0);
        createTestUser("admin", "admin", "master", TEST_USER, "password");
        //just making sure realm is not already present
        String token = keycloak.tokenManager().getAccessTokenString();
        RealmRepresentation nullRealm = null;
        try {
            nullRealm = exportRealm(token, TEST_REALM_NAME);
        } catch (HttpResponseException e) {
            Assert.assertEquals(404, e.getStatusCode());
        }
        Assert.assertNull(nullRealm);
        //end just making sure realm is not already present
    }

    @AfterClass
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

    @Deployment
    public static WebArchive deploy() {
        return ShrinkWrap.create(WebArchive.class, "run-on-server-classes.war")
                .addClasses(
                        ExportResourceProvider.class,
                        ExportResourceProviderFactory.class)
                .addAsManifestResource(new File("src/test/resources", "manifest.xml"))
                .addAsServiceProvider(RealmResourceProviderFactory.class, ExportResourceProviderFactory.class);
    }

    @Test
    public void adminCanExportMasterRealm() throws IOException {
        //TODO activate Full scope Mapping in admin-cli programmatically
        Keycloak keycloak = Keycloak.getInstance(KEYCLOAK_URL, "master", "admin", "admin", CLIENT);
        String token = keycloak.tokenManager().getAccessTokenString();
        RealmRepresentation realmRepresentation = exportRealm(token, "master");
        Assert.assertNotNull(realmRepresentation);
        Assert.assertEquals("master", realmRepresentation.getRealm());
        Assert.assertTrue(realmRepresentation.getUsers().stream().anyMatch(ur -> ur.getUsername().equals("admin")));
        Assert.assertTrue(realmRepresentation.getClients().size() > 0);
    }

    @Test
    public void importEqualsExport() throws IOException {
        try {
            Keycloak keycloak = Keycloak.getInstance(KEYCLOAK_URL, "master", "admin", "admin", CLIENT);
            String token = keycloak.tokenManager().getAccessTokenString();
            new ObjectMapper().readTree(new File(TEST_REALM_PATH));
            RealmRepresentation fileRepresentation = new ObjectMapper().readValue(new File(TEST_REALM_PATH), RealmRepresentation.class);
            Assert.assertNotNull(fileRepresentation);
            TestsHelper.importTestRealm("admin", "admin", "/" + TEST_REALM_NAME + "-realm.json");
            RealmRepresentation exportedRealm = exportRealm(token, TEST_REALM_NAME);
            Assert.assertEquals(fileRepresentation.getUsers().size(), exportedRealm.getUsers().size());
            //making sure all users are imported
            IntStream.range(0, fileRepresentation.getUsers().size()).forEach(i -> {
                UserRepresentation fileUser = fileRepresentation.getUsers().get(i);
                UserRepresentation exportedUser = exportedRealm.getUsers().parallelStream().filter(c -> c.getId().equals(fileUser.getId())).findAny().get();
                Assert.assertEquals(fileUser.getUsername(), exportedUser.getUsername());
                Assert.assertEquals(fileUser.getCredentials(), exportedUser.getCredentials());
                //making sure credentials are imported
                if (fileUser.getCredentials() != null && !fileUser.getCredentials().isEmpty()) {
                    Assert.assertEquals(fileUser.getCredentials().get(0).getHashedSaltedValue(), exportedUser.getCredentials().get(0).getHashedSaltedValue());
                }
            });
            //making sure client secrets are well imported and exported
            IntStream.range(0, fileRepresentation.getClients().size()).forEach(i -> {
                ClientRepresentation fileClient = fileRepresentation.getClients().get(i);
                ClientRepresentation exportedClient = exportedRealm.getClients().parallelStream().filter(c -> c.getId().equals(fileClient.getId())).findAny().get();
                Assert.assertEquals(fileClient.getId(), exportedClient.getId());
                Assert.assertEquals(fileClient.getName(), exportedClient.getName());
                Assert.assertEquals(fileClient.getSecret(), exportedClient.getSecret());
            });
            //groups...
            IntStream.range(0, fileRepresentation.getGroups().size()).forEach(i -> {
                GroupRepresentation fileGroup = fileRepresentation.getGroups().get(i);
                GroupRepresentation exportedGroup = exportedRealm.getGroups().parallelStream().filter(c -> c.getId().equals(fileGroup.getId())).findAny().get();
                Assert.assertEquals(fileGroup.getId(), exportedGroup.getId());
                Assert.assertEquals(fileGroup.getName(), exportedGroup.getName());
            });
            //realm roles (do not compare IDs, as they might be changed by the import mechanism)
            IntStream.range(0, fileRepresentation.getRoles().getRealm().size()).forEach(i -> {
                RoleRepresentation fileRealmRole = fileRepresentation.getRoles().getRealm().get(i);
                Optional<RoleRepresentation> exportRealmRoleOpt = exportedRealm.getRoles().getRealm().parallelStream().filter(c -> c.getName().equals(fileRealmRole.getName())).findAny();
                Assert.assertTrue(exportRealmRoleOpt.isPresent());
            });
            //clients roles
            fileRepresentation.getRoles().getClient().keySet().forEach(clientId -> {
                List<RoleRepresentation> fileClientRoles = fileRepresentation.getRoles().getClient().get(clientId);
                List<RoleRepresentation> exportedClientRoles = exportedRealm.getRoles().getClient().get(clientId);
                IntStream.range(0, fileClientRoles.size()).forEach(i -> {
                    RoleRepresentation fileClientRole = fileClientRoles.get(i);
                    RoleRepresentation exportedClientRole = exportedClientRoles.parallelStream().filter(c -> c.getId().equals(fileClientRole.getId())).findAny().get();
                    Assert.assertEquals(fileClientRole.getId(), exportedClientRole.getId());
                    Assert.assertEquals(fileClientRole.getName(), exportedClientRole.getName());
                });
            });
        } finally {
            //idempotence
            TestsHelper.deleteRealm("admin", "admin", TEST_REALM_NAME);
        }
    }

    @Test
    public void nonAdminCantExport() throws IOException {
        Keycloak keycloak = Keycloak.getInstance(KEYCLOAK_URL, "master", TEST_USER, "password", CLIENT);
        String token = keycloak.tokenManager().getAccessTokenString();
        expectedEx.expect(HttpResponseException.class);
        expectedEx.expect(hasProperty("statusCode", is(403)));
        exportRealm(token, "master");
    }


    @Test(expected = ForbiddenException.class)
    public void nonAdminCantBuiltInImport() throws IOException {
        RealmRepresentation fileRepresentation = new ObjectMapper().readValue(new File(TEST_REALM_PATH), RealmRepresentation.class);
        Assert.assertNotNull(fileRepresentation);
        TestsHelper.importTestRealm(TEST_USER, "password", "/" + TEST_REALM_NAME + "-realm.json");
        TestsHelper.deleteRealm("admin", "admin", TEST_REALM_NAME);
    }

    private static RealmRepresentation exportRealm(String token, String realm) throws IOException {
        CloseableHttpClient client = HttpClientBuilder.create().build();
        try {
            HttpGet get = new HttpGet(KEYCLOAK_URL + "/realms/" + realm + "/export/realm");
            get.addHeader("Authorization", "Bearer " + token);

            HttpResponse response = client.execute(get);
            if (response.getStatusLine().getStatusCode() != 200) {
                throw new HttpResponseException(response.getStatusLine().getStatusCode(), "export failed: " + response.getStatusLine().getStatusCode());
            }
            HttpEntity entity = response.getEntity();
            InputStream is = entity.getContent();
            ObjectMapper mapper = new ObjectMapper();
            try {
                return mapper.readValue(is, RealmRepresentation.class);
            } finally {
                is.close();
            }
        } finally {
            client.close();
        }
    }

    //TODO replace this with TestsHelper.createTestUser once issue KEYCLOAK-6807 is resolved
    private static boolean createTestUser(String username, String password, String realmName, String newUsername, String newPassword) throws IOException {
        Keycloak keycloak = Keycloak.getInstance(
                KEYCLOAK_URL,
                "master",
                username,
                password,
                CLIENT);
        UserRepresentation userRepresentation = new UserRepresentation();
        userRepresentation.setUsername(newUsername);
        userRepresentation.setEnabled(Boolean.TRUE);
        Response response = keycloak.realms().realm(realmName).users().create(userRepresentation);
        String userId = TestsHelper.getCreatedId(response);
        response.close();
        CredentialRepresentation rep = new CredentialRepresentation();
        rep.setType(CredentialRepresentation.PASSWORD);
        rep.setValue(newPassword);
        rep.setTemporary(false);
        keycloak.realms().realm(realmName).users().get(userId).resetPassword(rep);
        //add roles
        RoleRepresentation representation = new RoleRepresentation();
        representation.setName("user");

        keycloak.realms().realm(realmName).roles().create(representation);
        RoleRepresentation realmRole = keycloak.realms().realm(realmName).roles().get("user").toRepresentation();
        keycloak.realms().realm(realmName).users().get(userId).roles().realmLevel().add(Arrays.asList(realmRole));
        return true;

    }
}
