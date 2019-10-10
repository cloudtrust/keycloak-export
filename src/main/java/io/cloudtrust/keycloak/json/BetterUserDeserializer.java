package io.cloudtrust.keycloak.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudtrust.keycloak.export.dto.BetterUserRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class BetterUserDeserializer extends JsonDeserializer<List<UserRepresentation>> {
    @Override
    public List<UserRepresentation> deserialize(JsonParser jsonParser, DeserializationContext context) throws IOException {
        ObjectMapper objMapper = new ObjectMapper();
        List<UserRepresentation> res = new ArrayList<>();
        JsonNode jsonNode = jsonParser.readValueAsTree();
        Iterator<JsonNode> itr = jsonNode.elements();
        while (itr.hasNext()) {
            JsonNode credNode = itr.next();
            res.add(credNode.traverse(objMapper).readValueAs(BetterUserRepresentation.class));
        }
        return res;
    }
}
