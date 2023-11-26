#!/bin/bash

set -e

SCRIPT_DIR="$( cd "$( dirname -- "${BASH_SOURCE[0]}" )" && pwd )"
SL_ROOT="${SL_ROOT:-`pwd`}"
if [ -f "$SCRIPT_DIR/versions.sh" ]
then
  source "$SCRIPT_DIR/versions.sh"
else
    echo "$SCRIPT_DIR/versions.sh not found."
    exit 1
fi

SL_ARTIFACT_NAME=starlake-spark3_$SCALA_VERSION
SPARK_DIR_NAME=spark-$SPARK_VERSION-bin-hadoop$HADOOP_VERSION
SPARK_TARGET_FOLDER=$SCRIPT_DIR/bin/spark
SPARK_EXTRA_LIB_FOLDER=$SCRIPT_DIR/bin
DEPS_EXTRA_LIB_FOLDER=$SPARK_EXTRA_LIB_FOLDER/deps
STARLAKE_EXTRA_LIB_FOLDER=$SPARK_EXTRA_LIB_FOLDER/sl

#SPARK_EXTRA_PACKAGES="--packages io.delta:delta-core_2.12:2.4.0"
export SPARK_DRIVER_MEMORY="${SPARK_DRIVER_MEMORY:-4G}"
export SL_MAIN=ai.starlake.job.Main

#export SL_VALIDATE_ON_LOAD=false

if [ -n "$SL_VERSION" ]
then
  SL_JAR_NAME=$SL_ARTIFACT_NAME-$SL_VERSION-assembly.jar
fi


print_install_usage() {
  echo "Starlake is not installed yet. Please type 'starlake.sh install'."
  echo You can define the different env vars if you need to install specific versions.
  echo
  echo SL_VERSION: Support stable and snapshot version. Default to latest stable version
  echo SPARK_VERSION: default $SPARK_DEFAULT_VERSION
  echo HADOOP_VERSION: default $HADOOP_DEFAULT_VERSION

  # BIGQUERY
  echo
  echo 'ENABLE_BIGQUERY: enable or disable BIGQUERY dependencies (1 or 0). Default 0 - disabled'
  echo - SPARK_BQ_VERSION: default $SPARK_BQ_DEFAULT_VERSION

  # AZURE
  echo
  echo 'ENABLE_AZURE: enable or disable azure dependencies (1 or 0). Default 0 - disabled'
  echo - HADOOP_AZURE_VERSION: default $HADOOP_AZURE_DEFAULT_VERSION
  echo - AZURE_STORAGE_VERSION: default $AZURE_STORAGE_DEFAULT_VERSION
  echo - JETTY_VERSION: default $JETTY_DEFAULT_VERSION
  echo - JETTY_UTIL_VERSION: default to JETTY_VERSION
  echo - JETTY_UTIL_AJAX_VERSION: default to JETTY_VERSION

  # SNOWFLAKE
  echo
  echo 'ENABLE_SNOWFLAKE: enable or disable snowflake dependencies (1 or 0). Default 0 - disabled'
  echo - SPARK_SNOWFLAKE_VERSION: default $SPARK_SNOWFLAKE_DEFAULT_VERSION
  echo - SNOWFLAKE_JDBC_VERSION: default $SNOWFLAKE_JDBC_DEFAULT_VERSION
  echo
  echo Example:
  echo
  echo   ENABLE_SNOWFLAKE=1 starlake.sh install
  echo
  echo "Once installed, 'versions.sh' will be generated and pin dependencies' version."
  echo

  # POSTGRESQL
  echo
  echo 'ENABLE_POSTGRESQL: enable or disable snowflake dependencies (1 or 0). Default 0 - disabled'
  echo - POSTGRESQL_VERSION: default $POSTGRESQL_DEFAULT_VERSION
  echo
  echo Example:
  echo
  echo   ENABLE_POSTGRESQL=1 starlake.sh install
  echo
  echo "Once installed, 'versions.sh' will be generated and pin dependencies' version."
  echo

  # REDSHIFT
  echo
  echo 'ENABLE_REDSHIFT: enable or disable redshift dependencies (1 or 0). Default 0 - disabled'
  echo - AWS_JAVA_SDK_VERSION: default $AWS_JAVA_SDK_VERSION
  echo - HADOOP_AWS_VERSION: default $HADOOP_AWS_VERSION
  echo - REDSHIFT_JDBC_VERSION: default $REDSHIFT_JDBC_VERSION
  echo - SPARK_REDSHIFT_VERSION: default $SPARK_REDSHIFT_VERSION
  echo
  echo Example:
  echo
  echo   ENABLE_REDSHIFT=1 starlake.sh install
  echo
  echo "Once installed, 'versions.sh' will be generated and pin dependencies' version."
  echo
}

launch_setup() {
  if [ -n "${JAVA_HOME}" ]; then
    RUNNER="${JAVA_HOME}/bin/java"
  else
    if [ "$(command -v java)" ]; then
      RUNNER="java"
    else
      echo "JAVA_HOME is not set" >&2
      exit 1
    fi
  fi
  $RUNNER -cp "$SCRIPT_DIR" Setup "$SCRIPT_DIR"
}

launch_starlake() {
  if [ -f "$STARLAKE_EXTRA_LIB_FOLDER/$SL_JAR_NAME" ]
  then
    echo
    echo Launching starlake.
    echo "- JAVA_HOME=$JAVA_HOME"
    echo "- SL_ROOT=$SL_ROOT"
    echo "- SL_ENV=$SL_ENV"
    echo "- SL_MAIN=$SL_MAIN"
    echo "- SL_VALIDATE_ON_LOAD=$SL_VALIDATE_ON_LOAD"
    echo "- SPARK_DRIVER_MEMORY=$SPARK_DRIVER_MEMORY"
    echo Make sure your java home path does not contain space


    #if [[ $SL_FS = abfs:* ]] || [[ $SL_FS = wasb:* ]] || [[ $SL_FS = wasbs:* ]]
    #then
    #  if [[ -z "$AZURE_STORAGE_ACCOUNT" ]]
    #  then
    #    echo "AZURE_STORAGE_ACCOUNT should reference storage account name"
    #    exit 1
    #  fi
    #  if [[ -z "$AZURE_STORAGE_KEY" ]]
    #  then
    #    echo "AZURE_STORAGE_KEY should reference the storage account key"
    #    exit 1
    #  fi
    #  export SL_STORAGE_CONF="fs.azure.account.auth.type.$AZURE_STORAGE_ACCOUNT.blob.core.windows.net=SharedKey,
    #                  fs.azure.account.key.$AZURE_STORAGE_ACCOUNT.blob.core.windows.net="$AZURE_STORAGE_KEY",
    #                  fs.default.name=$SL_FS,
    #                  fs.defaultFS=$SL_FS"
    #fi

    if [[ -z "$SL_DEBUG" ]]
    then
      SPARK_DRIVER_OPTIONS="-Dlog4j.configuration=file://$SCRIPT_DIR/bin/spark/conf/log4j2.properties"
    else
      SPARK_DRIVER_OPTIONS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005 -Dlog4j.configuration=file://$SPARK_DIR/conf/log4j2.properties"
    fi

    if [[ "$SL_DEFAULT_LOADER" == "native" ]]
    then
      SL_ROOT=$SL_ROOT java \
                          --add-opens=java.base/java.lang=ALL-UNNAMED \
                          --add-opens=java.base/java.lang.invoke=ALL-UNNAMED \
                          --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
                          --add-opens=java.base/java.io=ALL-UNNAMED \
                          --add-opens=java.base/java.net=ALL-UNNAMED \
                          --add-opens=java.base/java.nio=ALL-UNNAMED \
                          --add-opens=java.base/java.util=ALL-UNNAMED \
                          --add-opens=java.base/java.util.concurrent=ALL-UNNAMED \
                          --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED \
                          --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
                          --add-opens=java.base/sun.nio.cs=ALL-UNNAMED \
                          --add-opens=java.base/sun.security.action=ALL-UNNAMED \
                          --add-opens=java.base/sun.util.calendar=ALL-UNNAMED \
                          --add-opens=java.security.jgss/sun.security.krb5=ALL-UNNAMED \
                          -Dlog4j.configurationFile="$SPARK_TARGET_FOLDER/conf/log4j2.properties" \
                          -cp "$SPARK_TARGET_FOLDER/jars/*:$DEPS_EXTRA_LIB_FOLDER/*:$STARLAKE_EXTRA_LIB_FOLDER/$SL_JAR_NAME" $SL_MAIN $@
    else
      extra_classpath="$STARLAKE_EXTRA_LIB_FOLDER/$SL_JAR_NAME"
      extra_jars="$STARLAKE_EXTRA_LIB_FOLDER/$SL_JAR_NAME"
      if [ $(ls "$DEPS_EXTRA_LIB_FOLDER/"*.jar | wc -l) -ne 0 ]
      then
        extra_classpath="$STARLAKE_EXTRA_LIB_FOLDER/$SL_JAR_NAME:$(echo "$DEPS_EXTRA_LIB_FOLDER/"*.jar | tr ' ' ':')"
        extra_jars="$STARLAKE_EXTRA_LIB_FOLDER/$SL_JAR_NAME,$(echo "$DEPS_EXTRA_LIB_FOLDER/"*.jar | tr ' ' ',')"
      fi
      SPARK_SUBMIT="$SPARK_TARGET_FOLDER/bin/spark-submit"
     SPARK_LOCAL_HOSTNAME="127.0.0.1" SPARK_HOME="$SCRIPT_DIR/bin/spark" SL_ROOT="$SL_ROOT" "$SPARK_SUBMIT" $SPARK_EXTRA_PACKAGES --driver-java-options "$SPARK_DRIVER_OPTIONS" $SPARK_CONF_OPTIONS --driver-class-path "$extra_classpath" --class "$SL_MAIN" --jars "$extra_jars" "$STARLAKE_EXTRA_LIB_FOLDER/$SL_JAR_NAME" "$@"
    fi
  else
    print_install_usage
  fi
}

case "$1" in
  install)
    launch_setup
    echo
    echo "Installation done. You're ready to enjoy Starlake!"
    echo If any errors happen during installation. Please try to install again or open an issue.
    ;;
  *)
    launch_starlake "$@"
    ;;
esac
