package io.cloudtrust.keycloak.export;

import io.cloudtrust.keycloak.export.dto.BetterCredentialRepresentation;
import org.jboss.logging.Logger;
import org.keycloak.common.ClientConnection;
import org.keycloak.common.util.Base64;
import org.keycloak.credential.CredentialModel;
import org.keycloak.exportimport.util.ExportUtils;
import org.keycloak.jose.jws.JWSInput;
import org.keycloak.jose.jws.JWSInputException;
import org.keycloak.models.AdminRoles;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.services.managers.AppAuthManager;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.managers.RealmManager;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.resources.admin.AdminAuth;
import org.keycloak.services.resources.admin.permissions.AdminPermissionEvaluator;
import org.keycloak.services.resources.admin.permissions.AdminPermissions;

import javax.ws.rs.*;
import javax.ws.rs.core.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ExportResourceProvider exposes two endpoints to import and export realms
 */
public class ExportResourceProvider implements RealmResourceProvider {

    protected static final Logger logger = Logger.getLogger(ExportResourceProvider.class);

    private KeycloakSession session;

    protected AppAuthManager authManager;

    @Context
    protected ClientConnection clientConnection;

    public ExportResourceProvider(KeycloakSession session) {
        this.session = session;
        this.authManager = new AppAuthManager();

    }

    @Override
    public Object getResource() {
        return this;
    }

    @GET
    @Path("realm")
    @Produces(MediaType.APPLICATION_JSON)
    public RealmRepresentation exportRealm(@Context final HttpHeaders headers, @Context final UriInfo uriInfo) {
        //retrieving the realm should be done before authentication
        // authentication overrides the value with master inside the context
        // this is done this way to avoid changing the copied code below (authenticateRealmAdminRequest)
        RealmModel realm = session.getContext().getRealm();
        AdminAuth adminAuth = authenticateRealmAdminRequest(headers, uriInfo);
        RealmManager realmManager = new RealmManager(session);
        RoleModel roleModel = adminAuth.getRealm().getRole(AdminRoles.ADMIN);
        AdminPermissionEvaluator realmAuth = AdminPermissions.evaluator(session, realm, adminAuth);
        if(roleModel != null && adminAuth.getUser().hasRole(roleModel)
                && adminAuth.getRealm().equals(realmManager.getKeycloakAdminstrationRealm())
                && realmAuth.realm().canManageRealm()){
            RealmRepresentation realmRep = ExportUtils.exportRealm(session, realm, true, true);
            //correct users
            if (realmRep.getUsers() != null) {
                setCorrectCredentials(realmRep.getUsers(), realm);
            }
            return realmRep;
        } else {
            throw new ForbiddenException();
        }
    }

    /**
     * This method rewrites the credential list for the users, including the Id (which is missing by default).
     * Unfortunately, due to the limitations in the keycloak API, there is no way to unit test this.
     * @param users The user representations to correct
     * @param realm the realm being exported
     */
    private void setCorrectCredentials(List <UserRepresentation> users, RealmModel realm) {
        Map<String, UserRepresentation> userRepMap = new HashMap<>();
        for (UserRepresentation userRep : users) {
            userRepMap.put(userRep.getId(), userRep);
        }

        for (UserModel user : session.users().getUsers(realm, true)) {
            // Credentials
            List<CredentialModel> creds = session.userCredentialManager().getStoredCredentials(realm, user);
            List<CredentialRepresentation> credReps = new ArrayList<CredentialRepresentation>();
            for (CredentialModel cred : creds) {
                CredentialRepresentation credRep = exportCredential(cred);
                credReps.add(credRep);
            }
            UserRepresentation userRep = userRepMap.get(user.getId());
            if (userRep != null) {
                userRep.setCredentials(credReps);
            }
        }
    }

    private BetterCredentialRepresentation exportCredential(CredentialModel userCred){
        BetterCredentialRepresentation credRep = new BetterCredentialRepresentation();
        credRep.setId(userCred.getId());
        credRep.setType(userCred.getType());
        credRep.setDevice(userCred.getDevice());
        credRep.setHashedSaltedValue(userCred.getValue());
        if (userCred.getSalt() != null) credRep.setSalt(Base64.encodeBytes(userCred.getSalt()));
        credRep.setHashIterations(userCred.getHashIterations());
        credRep.setCounter(userCred.getCounter());
        credRep.setAlgorithm(userCred.getAlgorithm());
        credRep.setDigits(userCred.getDigits());
        credRep.setCreatedDate(userCred.getCreatedDate());
        credRep.setConfig(userCred.getConfig());
        credRep.setPeriod(userCred.getPeriod());
        return credRep;
    }

    @Override
    public void close() {
    }

    /**
     * This code has been copied from keycloak org.keycloak.services.resources.admin.AdminRoot;
     * it allows to check if a user as realm/master admin
     * at each upgrade check that it hasn't been modified
     */
    protected AdminAuth authenticateRealmAdminRequest(HttpHeaders headers, UriInfo uriInfo) {
        String tokenString = authManager.extractAuthorizationHeaderToken(headers);
        if (tokenString == null) throw new NotAuthorizedException("Bearer");
        AccessToken token;
        try {
            JWSInput input = new JWSInput(tokenString);
            token = input.readJsonContent(AccessToken.class);
        } catch (JWSInputException e) {
            throw new NotAuthorizedException("Bearer token format error");
        }
        String realmName = token.getIssuer().substring(token.getIssuer().lastIndexOf('/') + 1);
        RealmManager realmManager = new RealmManager(session);
        RealmModel realm = realmManager.getRealmByName(realmName);
        if (realm == null) {
            throw new NotAuthorizedException("Unknown realm in token");
        }
        session.getContext().setRealm(realm);
        AuthenticationManager.AuthResult authResult = authManager.authenticateBearerToken(session, realm, uriInfo, clientConnection, headers);
        if (authResult == null) {
            logger.debug("Token not valid");
            throw new NotAuthorizedException("Bearer");
        }

        ClientModel client = realm.getClientByClientId(token.getIssuedFor());
        if (client == null) {
            throw new NotFoundException("Could not find client for authorization");

        }

        return new AdminAuth(realm, authResult.getToken(), authResult.getUser(), client);
    }
}
