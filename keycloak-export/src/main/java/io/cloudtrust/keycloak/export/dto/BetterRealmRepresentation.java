package io.cloudtrust.keycloak.export.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.cloudtrust.keycloak.json.BetterUserDeserializer;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.List;

public class BetterRealmRepresentation extends RealmRepresentation {
    @Override
    @JsonDeserialize(using = BetterUserDeserializer.class)
    public void setUsers(List<UserRepresentation> users) {
        this.users = users;
    }
}
