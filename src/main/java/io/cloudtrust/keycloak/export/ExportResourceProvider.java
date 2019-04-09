package io.cloudtrust.keycloak.export;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.authentication.AuthenticatorUtil;
import org.keycloak.common.ClientConnection;
import org.keycloak.exportimport.util.ExportUtils;
import org.keycloak.jose.jws.JWSInput;
import org.keycloak.jose.jws.JWSInputException;
import org.keycloak.models.AdminRoles;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.services.managers.AppAuthManager;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.managers.RealmManager;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.resources.admin.AdminAuth;
import org.keycloak.services.resources.admin.permissions.AdminPermissions;

import javax.ws.rs.*;
import javax.ws.rs.core.*;

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
        if(AdminPermissions.realms(session, adminAuth).isAdmin()){
            return ExportUtils.exportRealm(session, realm, true, true);
        } else {
            throw new ForbiddenException();
        }
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
