package io.cloudtrust.keycloak.importexport;

import org.jboss.logging.Logger;
import org.jboss.resteasy.spi.UnauthorizedException;
import org.keycloak.Config;
import org.keycloak.common.ClientConnection;
import org.keycloak.exportimport.ExportImportConfig;
import org.keycloak.exportimport.Strategy;
import org.keycloak.exportimport.util.ExportUtils;
import org.keycloak.exportimport.util.ImportUtils;
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
import org.keycloak.services.resources.admin.AdminRoot;

import javax.ws.rs.*;
import javax.ws.rs.core.*;

/**
 * ImportExportResourceProvider exposes two endpoints to import and export realms
 */
public class ImportExportResourceProvider implements RealmResourceProvider {

    protected static final Logger logger = Logger.getLogger(ImportExportResourceProvider.class);

    private KeycloakSession session;

    protected AppAuthManager authManager;

    @Context
    protected ClientConnection clientConnection;

    public ImportExportResourceProvider(KeycloakSession session) {
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
        String name = session.getContext().getRealm().getName();
        RealmModel realm = session.realms().getRealmByName(name);
        if (realm == null) throw new NotFoundException("Realm not found.");
        AdminAuth adminAuth = authenticateRealmAdminRequest(headers, uriInfo);
        if(isMasterAdmin(adminAuth)){
            return ExportUtils.exportRealm(session, realm, true, true);
        } else {
            throw new ForbiddenException("Forbidden");
        }
    }

    @POST
    @Path("realm")
    @Consumes(MediaType.APPLICATION_JSON)
    //TODO add strategy as param?
    public Response importRealm(final RealmRepresentation rep, @Context final HttpHeaders headers, @Context final UriInfo uriInfo) {
        String name = session.getContext().getRealm().getName();
        RealmModel urlRealm = session.realms().getRealmByName(name);
        if (urlRealm == null) throw new NotFoundException("Realm not found.");
        AdminAuth adminAuth = authenticateRealmAdminRequest(headers, uriInfo);
        if(canImportRealm(adminAuth, rep, urlRealm)){
                ImportUtils.importRealm(session, rep, ExportImportConfig.getStrategy(), false);
                return Response.ok().build();
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
        if (tokenString == null) throw new UnauthorizedException("Bearer");
        AccessToken token;
        try {
            JWSInput input = new JWSInput(tokenString);
            token = input.readJsonContent(AccessToken.class);
        } catch (JWSInputException e) {
            throw new UnauthorizedException("Bearer token format error");
        }
        String realmName = token.getIssuer().substring(token.getIssuer().lastIndexOf('/') + 1);
        RealmManager realmManager = new RealmManager(session);
        RealmModel realm = realmManager.getRealmByName(realmName);
        if (realm == null) {
            throw new UnauthorizedException("Unknown realm in token");
        }
        session.getContext().setRealm(realm);
        AuthenticationManager.AuthResult authResult = authManager.authenticateBearerToken(session, realm, uriInfo, clientConnection, headers);
        if (authResult == null) {
            logger.debug("Token not valid");
            throw new UnauthorizedException("Bearer");
        }

        ClientModel client = realm.getClientByClientId(token.getIssuedFor());
        if (client == null) {
            throw new NotFoundException("Could not find client for authorization");

        }

        return new AdminAuth(realm, authResult.getToken(), authResult.getUser(), client);
    }

    private boolean canImportRealm(AdminAuth auth, RealmRepresentation rep, RealmModel urlRealm) {
        if(session.realms().getRealmByName(rep.getRealm())!=null){
            // if realm exists
            // check if user can manage the realm his connected to (master admin for now)
            // and that he is connected to the realm he is trying to import(to avoid wrong import)
            return isMasterAdmin(auth) && urlRealm.getName().equals(rep.getRealm());
        } else {
            // if realm does not exist
            // check connected to Master and has Role Create Realm
            return Config.getAdminRealm().equals(auth.getRealm().getName()) &&
                    auth.hasRealmRole(AdminRoles.CREATE_REALM);
        }
    }

    private boolean isMasterAdmin(AdminAuth auth){
        return Config.getAdminRealm().equals(auth.getRealm().getName()) &&
                auth.hasRealmRole(AdminRoles.ADMIN);
    }

}
