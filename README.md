# Keycloak export Module

This module allows you to perform a full export from the REST-API, while keycloak is still running.

This version currently works with keycloak 6.0.1.

## Install keycloak-export

You need Java-8-x Java environment. To build, you must have the keycloak test artifacts in one of your repositories.
This can be done by downloading the keycloak source, and building it as recommended on their [webpage](https://github.com/keycloak/keycloak).
Make sure that you build the tag 6.0.1.

Run

```
mvn clean install
```

### Deploy manually

You can deploy as a module by running:

    $KEYCLOAK_HOME/bin/jboss-cli.sh --command="module add --name=io.cloudtrust.keycloak-export --resources=target/keycloak-export-6.0.1.jar --dependencies=org.keycloak.keycloak-core,org.keycloak.keycloak-server-spi,org.keycloak.keycloak-server-spi-private,org.keycloak.keycloak-services,javax.ws.rs.api"

Then registering the provider by editing `standalone/configuration/standalone.xml` and adding the module to the provider's element list:

    <providers>
        ...
        <provider>module:io.cloudtrust.keycloak-export</provider>
    </providers>

### Deploy automatically

Simply call the install.sh script with the base directory of keycloak as parameter. Then start (or restart) the server.

## Using the module

The module is used as for other REST-API endpoints (see [here](https://www.keycloak.org/docs/1.9/server_development_guide/topics/admin-rest-api.html)):

1) Export - Call the API to get an access token

```
curl \
  -d "client_id=admin-cli" \
  -d "username=admin" \
  -d "password=password" \
  -d "grant_type=password" \
  "http://localhost:8080/auth/realms/master/protocol/openid-connect/token"
```

Then call the `http://localhost:8080/auth/realms/master/export/realm` endpoint, using the token

```
curl \
  -H "Authorization: bearer eyJhbGciOiJSUz..." \
  "http://localhost:8080/auth/realms/master/export/realm"
```

You should see a JSON with the exported content.
You can also invoke the endpoint for other realms by replacing `master` with the realm name in the above URL.
Note that only an admin user in the master realm can call functions from this module.

2) Import through the user interface

* In Keycloak, go to the Themes configuration of the master realm
* Set the Admin Console Theme to `ctexport` then save 
* Log out and reconnect
* You are now able to `Add a realm` and select a file to import using this module's features

3) Import on Keycloak start-up

Import on start-up is described in Keycloak documentation (https://www.keycloak.org/docs/7.0/server_admin/index.html#_export_import)
To use this module to import realms, you have to use the provider `ctSingleFile`

Example:

```
${KEYCLOAK_HOME}/standalone/standalone.sh -Dkeycloak.migration.action=import -Dkeycloak.migration.provider=ctSingleFile -Dkeycloak.migration.file=myrealm.json
```

## Testing

Tests run with arquillian, as standard unit tests, similar to what is done on the keycloak project.
