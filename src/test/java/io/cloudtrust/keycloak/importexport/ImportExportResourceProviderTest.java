package io.cloudtrust.keycloak.importexport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpResponseException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.BasicHttpEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.hamcrest.Matcher;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.container.test.api.TargetsContainer;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.*;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
//import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.*;
import org.keycloak.services.resource.RealmResourceProviderFactory;
import org.keycloak.test.TestsHelper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.IntStream;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.hasProperty;

@RunWith(Arquillian.class)
@RunAsClient
public class ImportExportResourceProviderTest {

    private static final String MODULE_JAR = "keycloak-import-export";
    private static final String CLIENT = "admin-cli";
    private static ClientRepresentation clientBeforeChanges;
    private static final String TEST_REALM_NAME = "test-import-export";
    private static final String TEST_REALM_PATH = "src/test/resources/"+TEST_REALM_NAME+"-realm.json" ;

    @Rule
    public ExpectedException expectedEx = ExpectedException.none();

    @BeforeClass
    public static void initRealmAndUsers() throws IOException {
        Keycloak keycloak = Keycloak.getInstance(TestsHelper.keycloakBaseUrl, "master", "admin", "admin", CLIENT);
        clientBeforeChanges=keycloak.realms().realm("master").clients().findByClientId(CLIENT).get(0);
        if(!clientBeforeChanges.isFullScopeAllowed()){
            ClientRepresentation modifiedClient= keycloak.realms().realm("master").clients().get(clientBeforeChanges.getId()).toRepresentation();
            modifiedClient.setFullScopeAllowed(true);
            keycloak.realms().realm("master").clients().get(clientBeforeChanges.getId()).update(modifiedClient);
        }
        //just making sure realm is not already present
        String token=keycloak.tokenManager().getAccessTokenString();
        RealmRepresentation nullRealm=null;
        try{
            nullRealm=exportRealm(token, TEST_REALM_NAME);
        }catch (HttpResponseException e){
            Assert.assertEquals(e.getStatusCode(), 404);
        }
        Assert.assertNull(nullRealm);
        //end just making sure realm is not already present
    }

    @AfterClass
    public static void resetRealm(){
        //idempotence
        Keycloak keycloak = Keycloak.getInstance(TestsHelper.keycloakBaseUrl, "master", "admin", "admin", CLIENT);
        if(clientBeforeChanges!=null){
            keycloak.realms().realm("master").clients().get(clientBeforeChanges.getId()).update(clientBeforeChanges);
        }
    }

    @Deployment(name=MODULE_JAR, testable = false)
    @TargetsContainer("keycloak-remote")
    public static Archive<?> createProviderArchive() throws IOException {
        return ShrinkWrap.create(JavaArchive.class, "keycloak-import-export.jar")
                .addClasses(
                        ImportExportResourceProvider.class,
                        ImportExportResourceProviderFactory.class)
                .addAsManifestResource(new File("src/test/resources", "MANIFEST.MF"))
                .addAsServiceProvider(RealmResourceProviderFactory.class, ImportExportResourceProviderFactory.class);
    }

    @Test
    public void adminCanExportMasterRealm() throws IOException {
        //TODO activate Full scope Mapping in admin-cli programmatically
        Keycloak keycloak = Keycloak.getInstance(TestsHelper.keycloakBaseUrl, "master", "admin", "admin", "admin-cli");
        String token=keycloak.tokenManager().getAccessTokenString();
        RealmRepresentation realmRepresentation= exportRealm(token, "master");
        Assert.assertNotNull(realmRepresentation);
        Assert.assertEquals(realmRepresentation.getRealm(), "master");
        Assert.assertTrue(realmRepresentation.getUsers().stream().anyMatch(ur->ur.getUsername().equals("admin")));
        Assert.assertTrue(realmRepresentation.getClients().size()>0);
    }

    @Test
    public void adminCanImportTestRealm() throws IOException {
        try{
            Keycloak keycloak = Keycloak.getInstance(TestsHelper.keycloakBaseUrl, "master", "admin", "admin", "admin-cli");
            String token=keycloak.tokenManager().getAccessTokenString();
            RealmRepresentation fileRepresentation=new ObjectMapper().readValue(new File(TEST_REALM_PATH), RealmRepresentation.class);
            Assert.assertNotNull(fileRepresentation);
            importRealm(token, "master", fileRepresentation);
            RealmResource importedRealmResource = keycloak.realm(TEST_REALM_NAME);
            Assert.assertEquals(fileRepresentation.getRealm(), importedRealmResource.toRepresentation().getRealm());
            Assert.assertEquals(fileRepresentation.getUsers().size(), importedRealmResource.users().count().longValue());
            Assert.assertEquals(fileRepresentation.getClients().size(), importedRealmResource.clients().findAll().size());
            //make sure passwords are imported
            UserRepresentation user=importedRealmResource.users().search("user2").get(0);
            Assert.assertNotNull(user);
        }finally {
            //idempotence
            TestsHelper.deleteRealm("admin", "admin", TEST_REALM_NAME);
        }
    }

    @Test
    public void importEqualsExport() throws IOException {
        try{
            Keycloak keycloak = Keycloak.getInstance(TestsHelper.keycloakBaseUrl, "master", "admin", "admin", "admin-cli");
            String token=keycloak.tokenManager().getAccessTokenString();
            JsonNode fileJson=new ObjectMapper().readTree(new File(TEST_REALM_PATH));
            RealmRepresentation fileRepresentation=new ObjectMapper().readValue(new File(TEST_REALM_PATH), RealmRepresentation.class);
            Assert.assertNotNull(fileRepresentation);
            importRealm(token, "master", fileRepresentation);
            RealmRepresentation exportedRealm = exportRealm(token, TEST_REALM_NAME);
            Assert.assertEquals(fileRepresentation.getUsers().size(), exportedRealm.getUsers().size());
            //making sur all users are imported
            IntStream.range(0, fileRepresentation.getUsers().size()).forEach(i->{
                UserRepresentation fileUser = fileRepresentation.getUsers().get(i);
                UserRepresentation exportedUser = exportedRealm.getUsers().parallelStream().filter(c->c.getId().equals(fileUser.getId())).findAny().get();
                Assert.assertEquals(fileUser.getUsername(), exportedUser.getUsername());
                Assert.assertEquals(fileUser.getCredentials(), exportedUser.getCredentials());
                //making sure credentials are imported
                if(fileUser.getCredentials()!=null && !fileUser.getCredentials().isEmpty()){
                    Assert.assertEquals(fileUser.getCredentials().get(0).getHashedSaltedValue(), exportedUser.getCredentials().get(0).getHashedSaltedValue());
                }
            });
            //making sur client secrets are well imported and exported
            IntStream.range(0, fileRepresentation.getClients().size()).forEach(i->{
                ClientRepresentation fileClient = fileRepresentation.getClients().get(i);
                ClientRepresentation exportedClient = exportedRealm.getClients().parallelStream().filter(c->c.getId().equals(fileClient.getId())).findAny().get();
                Assert.assertEquals(fileClient.getId(), exportedClient.getId());
                Assert.assertEquals(fileClient.getName(), exportedClient.getName());
                Assert.assertEquals(fileClient.getSecret(), exportedClient.getSecret());
            });
            //groups...
            IntStream.range(0, fileRepresentation.getGroups().size()).forEach(i->{
                GroupRepresentation fileGroup = fileRepresentation.getGroups().get(i);
                GroupRepresentation exportedGroup = exportedRealm.getGroups().parallelStream().filter(c->c.getId().equals(fileGroup.getId())).findAny().get();
                Assert.assertEquals(fileGroup.getId(), exportedGroup.getId());
                Assert.assertEquals(fileGroup.getName(), exportedGroup.getName());
            });
            //realm roles
            IntStream.range(0, fileRepresentation.getRoles().getRealm().size()).forEach(i->{
                RoleRepresentation fileRealmRole = fileRepresentation.getRoles().getRealm().get(i);
                RoleRepresentation exportRealmRole = exportedRealm.getRoles().getRealm().parallelStream().filter(c->c.getId().equals(fileRealmRole.getId())).findAny().get();
                Assert.assertEquals(fileRealmRole.getId(), exportRealmRole.getId());
                Assert.assertEquals(fileRealmRole.getName(), exportRealmRole.getName());
            });
            //clients roles
            fileRepresentation.getRoles().getClient().keySet().forEach(clientId->{
                List<RoleRepresentation> fileClientRoles = fileRepresentation.getRoles().getClient().get(clientId);
                List<RoleRepresentation> exportedClientRoles = exportedRealm.getRoles().getClient().get(clientId);
                IntStream.range(0, fileClientRoles.size()).forEach(i->{
                    RoleRepresentation fileClientRole = fileClientRoles.get(i);
                    RoleRepresentation exportedClientRole = exportedClientRoles.parallelStream().filter(c->c.getId().equals(fileClientRole.getId())).findAny().get();
                    Assert.assertEquals(fileClientRole.getId(), exportedClientRole.getId());
                    Assert.assertEquals(fileClientRole.getName(), exportedClientRole.getName());
                });
            });
        }finally {
            //idempotence
            TestsHelper.deleteRealm("admin", "admin", TEST_REALM_NAME);
        }
    }

    @Test
    public void nonAdminCantExport() throws IOException {
        Keycloak keycloak = Keycloak.getInstance(TestsHelper.keycloakBaseUrl, "master", "user50", "password", "admin-cli");
        String token=keycloak.tokenManager().getAccessTokenString();
        expectedEx.expect(HttpResponseException.class);
        expectedEx.expect(hasProperty("statusCode", is(403)));
        exportRealm(token, "master");
    }

//    @Test
//    public void nonAdminCantImport() throws IOException {
//        TestsHelper.importTestRealm("admin", "admin", "");
//        Keycloak keycloak = Keycloak.getInstance(TestsHelper.keycloakBaseUrl, "master", "user50", "password", "admin-cli");
//        String token=keycloak.tokenManager().getAccessTokenString();
//        RealmRepresentation fileRepresentation=new ObjectMapper().readValue(new File(TEST_REALM_PATH), RealmRepresentation.class);
//        Assert.assertNotNull(fileRepresentation);
//        expectedEx.expect(HttpResponseException.class);
//        expectedEx.expect(hasProperty("statusCode", is(403)));
//        importRealm(token, TEST);
//    }

    @Test
    public void testBuiltInImport() throws IOException {
        try{
            RealmRepresentation fileRepresentation=new ObjectMapper().readValue(new File(TEST_REALM_PATH), RealmRepresentation.class);
            Assert.assertNotNull(fileRepresentation);
            TestsHelper.importTestRealm("admin", "admin", "/"+TEST_REALM_NAME+"-realm.json");
            Keycloak keycloak = Keycloak.getInstance(TestsHelper.keycloakBaseUrl, "master", "admin", "admin", "admin-cli");
            RealmResource importedRealmResource = keycloak.realm(TEST_REALM_NAME);
            Assert.assertEquals(fileRepresentation.getRealm(), importedRealmResource.toRepresentation().getRealm());
            Assert.assertEquals(fileRepresentation.getUsers().size(), importedRealmResource.users().count().longValue());
            Assert.assertEquals(fileRepresentation.getClients().size(), importedRealmResource.clients().findAll().size());
            //make sure passwords are imported
            UserRepresentation user=importedRealmResource.users().search("user2").get(0);
            Assert.assertNotNull(user);
        }finally {
            //idempotence
            TestsHelper.deleteRealm("admin", "admin", TEST_REALM_NAME);
        }
    }


//    @Test
//    public void adminCantImportExistingRealmWithWrongUrl() throws IOException {
//
//    }

    public static RealmRepresentation exportRealm(String token, String realm) throws IOException {
        CloseableHttpClient client = HttpClientBuilder.create().build();
        try {
            HttpGet get = new HttpGet(TestsHelper.keycloakBaseUrl + "/realms/"+realm+"/importexport/realm");
            get.addHeader("Authorization", "Bearer " + token);

            HttpResponse response = client.execute(get);
            if (response.getStatusLine().getStatusCode() != 200) {
                throw new HttpResponseException(response.getStatusLine().getStatusCode(), "export failed: "+response.getStatusLine().getStatusCode());
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

    public static void importRealm(String token, String urlRealm, RealmRepresentation realmRepresentation) throws IOException {
        CloseableHttpClient client = HttpClientBuilder.create().build();

        try {
            HttpPost post = new HttpPost(TestsHelper.keycloakBaseUrl + "/realms/"+urlRealm+"/importexport/realm");
            post.setEntity(new StringEntity(new ObjectMapper().writeValueAsString(realmRepresentation)));
            post.addHeader("Authorization", "Bearer " + token);
            post.addHeader("Content-type", "application/json");
            HttpResponse response = client.execute(post);
            int statusCode=response.getStatusLine().getStatusCode();
            if ( statusCode < 200 || statusCode >= 300) {
                throw new HttpResponseException(response.getStatusLine().getStatusCode(), "export failed");
            }
            HttpEntity entity = response.getEntity();

        } finally {
            client.close();
        }
    }
}
