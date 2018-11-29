#!/bin/bash
# install.sh
#
# install keycloak module :
# keycloak-export

set -e

#TODO: this value can be found in the module.xml in java path format -> get automatically?
MODULE_PATH="io/cloudtrust/keycloak-export/main"

usage ()
{
    echo "usage: $0 keycloak_path [-c]"
}


init()
{
    # deps
    [[ $(xmlstarlet --version) ]] || { echo >&2 "Requires xmlstarlet"; exit 1; }

    #optional args
    argv__NOCACHE=0;
    getopt_results=$(getopt -s bash -o c --long cluster, -- "$@")

    if test $? != 0
    then
        echo "unrecognized option"
        exit 1
    fi
    eval set -- "$getopt_results"

    while true
    do
        case "$1" in
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
    if [[ "argv_CLUSTER" ]]; then
        CONF_FILE=$argv__KEYCLOAK/standalone/configuration/standalone-ha.xml
    else
        CONF_FILE=$argv__KEYCLOAK/standalone/configuration/standalone.xml
    fi
    echo $CONF_FILE
    MODULE=${PWD##*/}
}

init_exceptions()
{
    EXCEPTION=0
    EXCEPTION_MSG=""
    #Main__Default_Unkown=1
    Main__ParameterException=2
}

Main__main()
{
    # init scipt temporals
    init_exceptions
    init "$@"
    # install module
    mvn package
    mkdir -p $argv__KEYCLOAK/modules/system/layers/$MODULE/$MODULE_PATH/
    cp target/$MODULE.jar $argv__KEYCLOAK/modules/system/layers/$MODULE/$MODULE_PATH/
    cp module.xml $argv__KEYCLOAK/modules/system/layers/$MODULE/$MODULE_PATH/
    sed -i "$ s/$/,$MODULE/" $argv__KEYCLOAK/modules/layers.conf
    xmlstarlet ed -L -N c="urn:jboss:domain:keycloak-server:1.1" -s /_:server/_:profile/c:subsystem/c:providers -t elem -n provider -v "module:io.cloudtrust.keycloak-export" $CONF_FILE
    exit 0
}

Main__main "$@"

