package io.cloudtrust.keycloak.export;

import org.keycloak.Config.Scope;
import org.keycloak.exportimport.ExportImportConfig;
import org.keycloak.exportimport.ImportProvider;
import org.keycloak.exportimport.ImportProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

import java.io.File;

public class SingleFileImportProviderFactory implements ImportProviderFactory {
    private static final String ID = "ctSingleFile";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public ImportProvider create(KeycloakSession session) {
        String fileName = ExportImportConfig.getFile();
        if (fileName == null) {
            throw new IllegalArgumentException("Property " + ExportImportConfig.FILE + " needs to be provided!");
        }
        return new SingleFileImportProvider(new File(fileName));
    }

    @Override
    public void init(Scope config) {
        // Nothing to init
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // Nothing to do
    }

    @Override
    public void close() {
        // Nothing to close
    }
}
