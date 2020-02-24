#!/usr/bin/env bash
set -e

VERSIONS_FILE="$(dirname $(realpath $0))/../kafka-versions.yaml"

# Gets the default Kafka version and sets "default_kafka_version" variable
# to the corresponding version string.
function get_default_kafka_version {

    finished=0
    counter=0
    default_kafka_version="null"
    while [ $finished -lt 1 ] 
    do
        version="$(yq read $VERSIONS_FILE [${counter}].version)"

        if [ "$version" = "null" ]
        then
            finished=1
        else
            if [ "$(yq read $VERSIONS_FILE [${counter}].default)" = "true" ]
            then
                if [ "$default_kafka_version" = "null" ]
                then
                    default_kafka_version=$version
                    finished=1
                else
                    # We have multiple defaults so there is an error in the versions file
                    >&2 echo "ERROR: There are multiple Kafka versions set as default"
                    unset default_kafka_version
                    exit 1
                fi
            fi
            counter=$((counter+1))
        fi
    done

    unset finished
    unset counter
    unset version

}

function get_kafka_versions {
    local val="$(yq read $VERSIONS_FILE '*.version' -j | tr '[],' '() ')"
    val=$(sed -e 's/^"//' -e 's/"$//' <<<"$val")
    IFS=$'\n' versions=($val)
}

function get_kafka_urls {
    local val="$(yq read $VERSIONS_FILE '*.url' -j | tr '[],' '() ')"
    val=$(sed -e 's/^"//' -e 's/"$//' <<<"$val")
    IFS=$'\n' binary_urls=($val)
}

function get_zookeeper_versions {
    local val="$(yq read $VERSIONS_FILE '*.zookeeper' -j | tr '[],' '() ')"
    val=$(sed -e 's/^"//' -e 's/"$//' <<<"$val")
    IFS=$'\n' zk_versions=($val)
}

function get_kafka_checksums {
    local val="$(yq read $VERSIONS_FILE '*.checksum' -j | tr '[],' '() ')"
    val=$(sed -e 's/^"//' -e 's/"$//' <<<"$val")
    IFS=$'\n' checksums=($val)
}

function get_kafka_third_party_libs {
    local val="$(yq read "$VERSIONS_FILE" '*.third-party-libs' -j | tr '[],' '() ')"
    val=$(sed -e 's/^"//' -e 's/"$//' <<<"$val")
    IFS=$'\n' libs=($val)
}

function get_kafka_protocols {
    local val="$(yq read $VERSIONS_FILE '*.protocol' -j | tr '[],' '() ')"
    val=$(sed -e 's/^"//' -e 's/"$//' <<<"$val")
    IFS=$'\n' protocols=($val)
}

function get_kafka_formats {
    local val="$(yq read $VERSIONS_FILE '*.format' -j | tr '[],' '() ')"
    val=$(sed -e 's/^"//' -e 's/"$//' <<<"$val")
    IFS=$'\n' formats=($val)
}

function get_kafka_does_not_support {
    local val="$(yq read $VERSIONS_FILE '*.unsupported-features' -j | tr '[],' '() ')"
    val=$(sed -e 's/^"//' -e 's/"$//' <<<"$val")
    IFS=$'\n' does_not_support=($val)

    get_kafka_versions

    declare -Ag version_does_not_support
    for i in "${!versions[@]}"
    do
        echo "does_not_support: ${versions[$i]} = ${does_not_support[$i]}"
        version_does_not_support[${versions[$i]}]=${does_not_support[$i]}
    done
}

# Parses the Kafka versions file and creates three associative arrays:
# "version_binary_urls": Maps from version string to url from which the kafka source 
# tar will be downloaded.
# "version_checksums": Maps from version string to sha512 checksum.
# "version_libs": Maps from version string to third party library version string.
function get_version_maps {

    get_kafka_versions
    get_kafka_urls
    get_kafka_checksums
    get_kafka_third_party_libs

    declare -Ag version_binary_urls
    declare -Ag version_checksums
    declare -Ag version_libs

    for i in "${!versions[@]}"
    do 
        version_binary_urls[${versions[$i]}]=${binary_urls[$i]}
        version_checksums[${versions[$i]}]=${checksums[$i]}
        version_libs[${versions[$i]}]=${libs[$i]}
    done
    
}
