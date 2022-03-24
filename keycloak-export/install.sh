#!/bin/bash
# install.sh
#
# install keycloak module :
# keycloak-export

set -eE
MODULE_DIR=$(dirname $0)
TARGET_DIR=$MODULE_DIR/target
[ -z "$WINDIR" ] && KC_EXE=kc.sh || KC_EXE=kc.bat

usage ()
{
    echo "usage: $0 keycloak_path [-c]"
}

abort_usage_keycloak()
{
  echo "Invalid keycloak path"
  usage
  exit 1
}

init()
{
    # deps
    [[ $(xmlstarlet --version) ]] || { echo >&2 "Requires xmlstarlet"; exit 1; }

    #optional args
    argv__CLUSTER=0;
    argv__UNINSTALL=0
    argv__TARGET="http://localhost:8898"
    getopt_results=$(getopt -s bash -o ct:u --long cluster,target:,uninstall -- "$@")

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
            -t|--target)
                argv__TARGET="$2"
                echo "--target set to \"$argv__TARGET\". Will edit papercard target URI"
                shift 2
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
    [ -d $argv__KEYCLOAK ] && [ -d $argv__KEYCLOAK/bin ] && [ -d $argv__KEYCLOAK/providers ] && [ -d $argv__KEYCLOAK/conf ] || abort_usage_keycloak
    # optional args
    CONF_FILE=$argv__KEYCLOAK/conf/keycloak.conf
    echo $CONF_FILE
    MODULE_NAME=$(xmlstarlet sel -N oe="urn:jboss:module:1.3" -t -v '/oe:module/@name' -n $MODULE_DIR/module.xml)
    MODULE=${MODULE_NAME##*.}
    JAR_PATH=`find ${TARGET_DIR} -type f -name "*.jar" -not -name "*sources.jar" | grep -v "archive-tmp"`
    JAR_NAME=`basename $JAR_PATH`
}

init_exceptions()
{
    EXCEPTION=0
    EXCEPTION_MSG=""
    #Main__Default_Unkown=1
    Main__ParameterException=2
}

del_configuration()
{
  if [[ ! -z "$1" ]] ; then
    sed -i "/^$1=/d" ${CONF_FILE}
  fi
}

add_configuration()
{
  if [[ ! -z "$1" ]] ; then
    sed -i "/^$1=/d" ${CONF_FILE}
    echo "$1=$2" >> ${CONF_FILE}
  fi
}

cleanup()
{
    #clean dir structure in case of script failure
    echo "cleanup..."

    #del_configuration spi-xxxxxxxxxxxxxx
    rm -rf $argv__KEYCLOAK/providers/$MODULE

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
    # init script temporals
    init_exceptions
    init "$@"
    if [[ "$argv__UNINSTALL" -eq 1 ]]; then
        cleanup
        exit 0
    fi
    # install module
    cp $JAR_PATH $argv__KEYCLOAK/providers/

    # configure module
    #add_configuration key value
    $argv__KEYCLOAK/bin/$KC_EXE build

    exit 0
}

Main__main "$@"
