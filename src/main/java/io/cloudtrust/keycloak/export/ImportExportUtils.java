package io.cloudtrust.keycloak.export;

import io.cloudtrust.keycloak.export.dto.BetterRealmRepresentation;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.services.managers.RealmManager;
import org.keycloak.services.resources.KeycloakApplication;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public class ImportExportUtils {
    public static RealmModel importRealm(KeycloakSession session, KeycloakApplication keycloak, BetterRealmRepresentation rep) {
        RealmManager realmManager = new RealmManager(session);
        if (keycloak != null) {
            realmManager.setContextPath(keycloak.getContextPath());
        }

        // Cache required actions information and remove it from user representations..
        // Original version from RepresentationToModel first convert it to an enum
        // then get the name of the enum value. This fails for customized required actions
        Map<UserRepresentation, List<String>> mapUserToRequiredActions = new HashMap<>();
        for (UserRepresentation user : rep.getUsers()) {
            if (user.getRequiredActions() != null) {
                mapUserToRequiredActions.put(user, user.getRequiredActions());
                user.setRequiredActions(Collections.emptyList());
            }
        }

        // Basic import
        RealmModel realm = realmManager.importRealm(rep);

        // Now set required actions
        for (Entry<UserRepresentation, List<String>> entry : mapUserToRequiredActions.entrySet()) {
            UserRepresentation userRep = entry.getKey();
            UserModel user = session.userLocalStorage().getUserById(userRep.getId(), realm);
            for (String requiredAction : entry.getValue()) {
                user.addRequiredAction(requiredAction.toUpperCase());
            }
        }

        return realm;
    }
}
