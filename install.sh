#!/bin/bash
# install.sh
#
# install keycloak module :
# keycloak-export

set -e

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
    CONF_FILE=standalone-ha.xml
    MODULE=${PWD##*/}
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
    echo "cleanup:"
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
    # @description signal handler for end of the program (clean or unclean).
    # probably redundant call, we already call the cleanup in main.
    cleanup
    if [[ "$EXCEPTION" -ne 0 ]] ; then
        echo "$0: error : ${EXCEPTION_MSG}"
    fi
    exit
}

trap Main__interruptHandler INT
trap Main__terminationHandler TERM
trap Main__exitHandler EXIT

Main__main()
{
    # init scipt temporals
    init_exceptions
    init "$@"
    # install module
    mvn package
    mkdir $argv__KEYCLOAK/modules/system/layers/$MODULE
    cp target/$MODULE.jar $argv__KEYCLOAK/modules/system/layers/$MODULE
    sed -i "$ s/$/,$MODULE/" $argv__KEYCLOAK/modules/layers.conf
    xmlstarlet ed -L -N c="urn:jboss:domain:keycloak-server:1.1" -s /_:server/_:profile/c:subsystem/c:providers -t elem -n provider -v "module:io.cloudtrust.keycloak-export" $CONF_FILE
    exit 0
}

# catch signals and exit
#trap exit INT TERM EXIT

Main__main "$@"

