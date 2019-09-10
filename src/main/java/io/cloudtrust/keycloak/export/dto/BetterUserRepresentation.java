package io.cloudtrust.keycloak.export.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.cloudtrust.keycloak.json.BetterCredentialDeserializer;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.List;

public class BetterUserRepresentation extends UserRepresentation {
    @Override
    @JsonDeserialize(using = BetterCredentialDeserializer.class)
    public void setCredentials(List<CredentialRepresentation> credentials) {
        super.setCredentials(credentials);
    }
}
