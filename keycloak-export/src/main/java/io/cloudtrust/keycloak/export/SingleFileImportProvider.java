package io.cloudtrust.keycloak.export;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudtrust.keycloak.export.dto.BetterRealmRepresentation;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.exportimport.ImportProvider;
import org.keycloak.exportimport.Strategy;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.services.managers.RealmManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class SingleFileImportProvider implements ImportProvider {
    private static final Logger logger = Logger.getLogger(SingleFileImportProvider.class);
    private static final ThreadLocal<ObjectMapper> objMapperProvider = ThreadLocal.withInitial(ObjectMapper::new);

    private final File file;

    // Allows to cache representation per provider to avoid parsing them twice
    private List<BetterRealmRepresentation> realmReps;

    public SingleFileImportProvider(File file) {
        this.file = file;
    }

    @Override
    public void importModel(KeycloakSessionFactory factory, Strategy strategy) throws IOException {
        logger.infof("Full importing from file %s", this.file.getAbsolutePath());

        BetterRealmRepresentation masterRealm = getMasterRealm();
        KeycloakModelUtils.runJobInTransaction(factory, session -> {
            // Import master realm first, if exists
            if (masterRealm != null) {
                importRealm(session, masterRealm, strategy);
            }
            realmReps.stream().filter(r -> r != masterRealm).forEach(r -> importRealm(session, r, strategy));

            if (masterRealm != null) {
                // If master was imported, we may need to re-create realm management clients
                for (RealmModel realm : session.realms().getRealms()) {
                    if (realm.getMasterAdminClient() == null) {
                        logger.infof("Re-created management client in master realm for realm '%s'", realm.getName());
                        new RealmManager(session).setupMasterAdminManagement(realm);
                    }
                }
            }
        });
    }

    @Override
    public void importRealm(KeycloakSessionFactory factory, String realmName, Strategy strategy) throws IOException {
        // import just that single realm in case that file contains many realms?
        importModel(factory, strategy);
    }

    @Override
    public boolean isMasterRealmExported() throws IOException {
        return getMasterRealm() != null;
    }

    @Override
    public void close() {
        // Nothing to close
    }

    private void importRealm(KeycloakSession session, BetterRealmRepresentation realm, Strategy strategy) {
        ImportExportUtils.importRealm(session, null, realm, strategy, false);
    }

    private BetterRealmRepresentation getMasterRealm() throws IOException {
        checkRealmReps();
        return realmReps.stream().filter(r -> Config.getAdminRealm().equals(r.getRealm())).findFirst().orElse(null);
    }

    private void checkRealmReps() throws IOException {
        if (realmReps == null) {
            try (InputStream is = new FileInputStream(file)) {
                realmReps = getObjectsFromStream(objMapperProvider.get(), is, BetterRealmRepresentation.class);
            }
        }
    }

    private static <T> List<T> getObjectsFromStream(ObjectMapper mapper, InputStream is, Class<T> clazz) throws IOException {
        List<T> result = new ArrayList<>();
        JsonFactory factory = mapper.getFactory();
        try (JsonParser parser = factory.createParser(is)) {
            parser.nextToken();

            if (parser.getCurrentToken() == JsonToken.START_ARRAY) {
                // Case with more realms in stream
                parser.nextToken();

                while (parser.getCurrentToken() == JsonToken.START_OBJECT) {
                    T realmRep = parser.readValueAs(clazz);
                    parser.nextToken();
                    result.add(realmRep);
                }
            } else if (parser.getCurrentToken() == JsonToken.START_OBJECT) {
                // Case with single realm in stream
                T realmRep = parser.readValueAs(clazz);
                result.add(realmRep);
            }
        }

        return result;
    }
}
