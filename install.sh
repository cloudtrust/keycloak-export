#!/bin/bash
# install.sh
#
# install keycloak module :
# keycloak-export

set -eE


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
    if [[ "$argv__CLUSTER" ]]; then
        CONF_FILE=$argv__KEYCLOAK/standalone/configuration/standalone-ha.xml
    else
        CONF_FILE=$argv__KEYCLOAK/standalone/configuration/standalone.xml
    fi
    echo $CONF_FILE
    MODULE=${PWD##*/}
    MODULE_PATH=$(xmlstarlet sel -N oe="urn:jboss:module:1.3" -t -v '/oe:module/@name' -n module.xml)
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
    xmlstarlet ed -L -N c="urn:jboss:domain:keycloak-server:1.1" -d "/_:server/_:profile/c:subsystem/c:providers/c:provider[text()='module:io.cloudtrust.keycloak-export']" $CONF_FILE
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
    mvn package
    mkdir -p $argv__KEYCLOAK/modules/system/layers/$MODULE/$MODULE_PATH/
    cp target/$MODULE.jar $argv__KEYCLOAK/modules/system/layers/$MODULE/$MODULE_PATH/
    cp module.xml $argv__KEYCLOAK/modules/system/layers/$MODULE/$MODULE_PATH/
    if ! grep -q "$MODULE" "$argv__KEYCLOAK/modules/layers.conf"; then
        sed -i "$ s/$/,$MODULE/" $argv__KEYCLOAK/modules/layers.conf
    fi
    # FIXME make this reentrant then test
    xmlstarlet ed -L -N c="urn:jboss:domain:keycloak-server:1.1" -s /_:server/_:profile/c:subsystem/c:providers -t elem -n provider -v "module:io.cloudtrust.keycloak-export" $CONF_FILE
    exit 0
}

Main__main "$@"

