package io.cloudtrust.keycloak.export;

import io.cloudtrust.keycloak.export.dto.BetterCredentialRepresentation;
import io.cloudtrust.keycloak.export.dto.BetterRealmRepresentation;
import org.keycloak.common.util.Base64;
import org.keycloak.credential.CredentialModel;
import org.keycloak.models.*;
import org.keycloak.models.credential.PasswordUserCredentialModel;
import org.keycloak.models.utils.HmacOTP;
import org.keycloak.models.utils.RepresentationToModel;
import org.keycloak.policy.PasswordPolicyNotMetException;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.services.managers.RealmManager;
import org.keycloak.services.resources.KeycloakApplication;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public class ImportExportUtils {
    public static RealmModel importRealm(KeycloakSession session, KeycloakApplication keycloak, BetterRealmRepresentation rep) {
        // Cache required actions information and remove it from user representations..
        // Original version from RepresentationToModel first convert it to an enum
        // then get the name of the enum value. This fails for customized required actions
        Map<UserRepresentation, List<CredentialRepresentation>> mapUserToCreds = new HashMap<>();
        Map<UserRepresentation, List<String>> mapUserToRequiredActions = new HashMap<>();
        for (UserRepresentation user : rep.getUsers()) {
            // Credentials
            mapUserToCreds.put(user, user.getCredentials());
            user.setCredentials(new ArrayList<>());
            // Required actions
            if (user.getRequiredActions()!=null) {
                mapUserToRequiredActions.put(user, user.getRequiredActions());
                user.setRequiredActions(Collections.emptyList());
            }
        }

        RealmManager realmManager = new RealmManager(session);
        if (keycloak != null) {
            realmManager.setContextPath(keycloak.getContextPath());
        }

        RealmModel realm = realmManager.importRealm(rep);

        // Now set credentials
        mapUserToCreds.forEach((u, creds) -> {
            UserModel user = session.userLocalStorage().getUserById(u.getId(), realm);
            for (CredentialRepresentation cred : creds) {
                updateCredential(session, realm, user, (BetterCredentialRepresentation) cred, false);
            }
        });
        // Now set required actions
        for (Entry<UserRepresentation, List<String>> entry : mapUserToRequiredActions.entrySet()) {
            UserRepresentation userRep = entry.getKey();
            UserModel user = session.userLocalStorage().getUserById(userRep.getId(), realm);
            for (String requiredAction : entry.getValue()) {
                user.addRequiredAction(requiredAction);
            }
        }

        return realm;
    }

    /**
     * Inspired by RepresentationToModel.updateCredential
     */
    private static void updateCredential(KeycloakSession session, RealmModel realm, UserModel user, BetterCredentialRepresentation cred, boolean adminRequest) {
        if (cred.getValue() != null) {
            updateCredentialPlainText(session, realm, user, cred, adminRequest);
        } else {
            updateCredentialHashed(session, realm, user, cred);
        }
    }

    private static void updateCredentialPlainText(KeycloakSession session, RealmModel realm, UserModel user, BetterCredentialRepresentation cred, boolean adminRequest) {
        PasswordUserCredentialModel plainTextCred = RepresentationToModel.convertCredential(cred);
        plainTextCred.setAdminRequest(adminRequest);

        //if called from import we need to change realm in context to load password policies from the newly created realm
        RealmModel origRealm = session.getContext().getRealm();
        try {
            session.getContext().setRealm(realm);
            session.userCredentialManager().updateCredential(realm, user, plainTextCred);
        } catch (ModelException ex) {
            throw new PasswordPolicyNotMetException(ex.getMessage(), user.getUsername(), ex);
        } finally {
            session.getContext().setRealm(origRealm);
        }
    }

    public static void updateCredentialHashed(KeycloakSession session, RealmModel realm, UserModel user, BetterCredentialRepresentation cred) {
        CredentialModel hashedCred = new CredentialModel();
        hashedCred.setId(cred.getId());
        hashedCred.setType(cred.getType());
        hashedCred.setDevice(cred.getDevice());
        if (cred.getHashIterations() != null) {
            hashedCred.setHashIterations(cred.getHashIterations());
        }
        try {
            if (cred.getSalt() != null) {
                hashedCred.setSalt(Base64.decode(cred.getSalt()));
            }
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
        hashedCred.setValue(cred.getHashedSaltedValue());
        if (cred.getCounter() != null) {
            hashedCred.setCounter(cred.getCounter());
        }
        if (cred.getDigits() != null) {
            hashedCred.setDigits(cred.getDigits());
        }

        boolean isPasswordOrPasswordHistory = UserCredentialModel.PASSWORD.equals(cred.getType()) || UserCredentialModel.PASSWORD_HISTORY.equals(cred.getType());
        if (cred.getAlgorithm() != null) {
            // Could happen when migrating from some early version
            if (isPasswordOrPasswordHistory && cred.getAlgorithm().equals(HmacOTP.HMAC_SHA1)) {
                hashedCred.setAlgorithm("pbkdf2");
            } else {
                hashedCred.setAlgorithm(cred.getAlgorithm());
            }
        } else if (isPasswordOrPasswordHistory) {
            hashedCred.setAlgorithm("pbkdf2");
        } else if (UserCredentialModel.isOtp(cred.getType())) {
            hashedCred.setAlgorithm(HmacOTP.HMAC_SHA1);
        }

        if (cred.getPeriod() != null) {
            hashedCred.setPeriod(cred.getPeriod());
        }
        if (cred.getDigits() == null && UserCredentialModel.isOtp(cred.getType())) {
            hashedCred.setDigits(6);
        }
        if (cred.getPeriod() == null && UserCredentialModel.TOTP.equals(cred.getType())) {
            hashedCred.setPeriod(30);
        }
        hashedCred.setCreatedDate(cred.getCreatedDate());
        session.userCredentialManager().createCredential(realm, user, hashedCred);
    }
}
