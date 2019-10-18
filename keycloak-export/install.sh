#!/bin/bash
# install.sh
#
# install keycloak module :
# keycloak-export

set -eE
MODULE_DIR=$(dirname $0)
TARGET_DIR=${MODULE_DIR}/target

usage ()
{
    echo "usage: $0 keycloak_path [-c]"
}


init()
{
    # deps
    [[ $(xmlstarlet --version) ]] || { echo >&2 "Requires xmlstarlet"; exit 1; }

    #optional args
    argv__CLUSTER=0;
    argv__UNINSTALL=0
    getopt_results=$(getopt -s bash -o cu --long cluster,uninstall -- "$@")

    if test $? != 0
    then
        echo "unrecognized option"
        exit 1
    fi
    eval set -- "$getopt_results"

    while true
    do
        case "$1" in
            -u|--uninstall)
                argv__UNINSTALL=1
                echo "--delete set. will remove plugin"
                shift
                ;;
            -c|--cluster)
                argv__CLUSTER=1
                echo "--cluster set. Will edit cluster config"
                shift
                ;;
            --)
                shift
                break
                ;;
            *)
                EXCEPTION=$Main__ParameterException
                EXCEPTION_MSG="unparseable option $1"
                exit 1
                ;;
        esac
    done

    # positional args
    argv__KEYCLOAK=""
    if [[ "$#" -ne 1 ]]; then
        usage
        exit 1
    fi
    argv__KEYCLOAK="$1"
    # optional args
    CONF_FILE=""
    if [[ "$argv__CLUSTER" -eq 1 ]]; then
        CONF_FILE=$argv__KEYCLOAK/standalone/configuration/standalone-ha.xml
    else
        CONF_FILE=$argv__KEYCLOAK/standalone/configuration/standalone.xml
    fi
    echo $CONF_FILE
    MODULE_NAME=$(xmlstarlet sel -N oe="urn:jboss:module:1.3" -t -v '/oe:module/@name' -n $MODULE_DIR/module.xml)
    MODULE=${MODULE_NAME##*.}
    JAR_PATH=`find $TARGET_DIR/ -type f -name "*.jar" -not -name "*sources.jar"`
    JAR_NAME=`basename $JAR_PATH`
    MODULE_PATH=${MODULE_NAME//./\/}/main
}

init_exceptions()
{
    EXCEPTION=0
    EXCEPTION_MSG=""
    #Main__Default_Unkown=1
    Main__ParameterException=2
}

cleanup()
{
    #clean dir structure in case of script failure
    echo "cleanup..."
    xmlstarlet ed -L -N c="urn:jboss:domain:keycloak-server:1.1" -d "/_:server/_:profile/c:subsystem/c:providers/c:provider[text()='module:$MODULE_NAME']" $CONF_FILE
    xmlstarlet ed -L -N c="urn:jboss:domain:keycloak-server:1.1" -d "/_:server/_:profile/c:subsystem/c:theme/c:modules/c:module[text()='$MODULE_NAME']" $CONF_FILE
    sed -i "$ s/,$MODULE$//" $argv__KEYCLOAK/modules/layers.conf
    rm -rf $argv__KEYCLOAK/modules/system/layers/$MODULE
    echo "done"
}

Main__interruptHandler()
{
    # @description signal handler for SIGINT
    echo "$0: SIGINT caught"
    exit
}
Main__terminationHandler()
{
    # @description signal handler for SIGTERM
    echo "$0: SIGTERM caught"
    exit
}
Main__exitHandler()
{
    cleanup
    if [[ "$EXCEPTION" -ne 0 ]] ; then
        echo "$0: error : ${EXCEPTION_MSG}"
    fi
    exit
}

trap Main__interruptHandler INT
trap Main__terminationHandler TERM
trap Main__exitHandler ERR

Main__main()
{
    # init scipt temporals
    init_exceptions
    init "$@"
    if [[ "$argv__UNINSTALL" -eq 1 ]]; then
        cleanup
        exit 0
    fi
    # install module
    mkdir -p $argv__KEYCLOAK/modules/system/layers/$MODULE/$MODULE_PATH/
    cp $JAR_PATH $argv__KEYCLOAK/modules/system/layers/$MODULE/$MODULE_PATH/
    cp $MODULE_DIR/module.xml $argv__KEYCLOAK/modules/system/layers/$MODULE/$MODULE_PATH/
    sed -i "s@JAR_NAME@${JAR_NAME}@g" $argv__KEYCLOAK/modules/system/layers/$MODULE/$MODULE_PATH/module.xml
    if ! grep -q "$MODULE" "$argv__KEYCLOAK/modules/layers.conf"; then
        sed -i "$ s/$/,$MODULE/" $argv__KEYCLOAK/modules/layers.conf
    fi
    # FIXME make this reentrant then test
    xmlstarlet ed -L -N c="urn:jboss:domain:keycloak-server:1.1" -s /_:server/_:profile/c:subsystem/c:providers -t elem -n provider -v "module:$MODULE_NAME" $CONF_FILE

    MODULES_EXISTS=`xmlstarlet sel -N c="urn:jboss:domain:keycloak-server:1.1" -t -v "count(/_:server/_:profile/c:subsystem/c:theme/c:modules/c:module)" $CONF_FILE`
    if [ $MODULES_EXISTS -eq "0" ]; then
        xmlstarlet ed -L -N c="urn:jboss:domain:keycloak-server:1.1" -d /_:server/_:profile/c:subsystem/c:theme/c:modules $CONF_FILE
        xmlstarlet ed -L -N c="urn:jboss:domain:keycloak-server:1.1" -s /_:server/_:profile/c:subsystem/c:theme -t elem -n modules $CONF_FILE
    fi
    xmlstarlet ed -L -N c="urn:jboss:domain:keycloak-server:1.1" -s /_:server/_:profile/c:subsystem/c:theme/c:modules -t elem -n module -v "$MODULE_NAME" $CONF_FILE
    exit 0
}

Main__main "$@"
