package io.cloudtrust.keycloak.export;

import io.cloudtrust.keycloak.export.dto.BetterRealmRepresentation;
import org.keycloak.models.*;
import org.keycloak.services.managers.RealmManager;
import org.keycloak.services.resources.KeycloakApplication;

public class ImportExportUtils {
    public static RealmModel importRealm(KeycloakSession session, KeycloakApplication keycloak, BetterRealmRepresentation rep) {
        RealmManager realmManager = new RealmManager(session);
        if (keycloak != null) {
            realmManager.setContextPath(keycloak.getContextPath());
        }

        return realmManager.importRealm(rep);
    }
}
