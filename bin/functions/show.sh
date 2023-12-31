#!/bin/sh
IFS='' read -r -d '' SHOW_HELP <<'EOH'

Usage: cmr show [SUBCOMMANDS]

Defined subcommands:

    cmr-port APP     - Show the default port for the given CMR port.
    help             - Show this message.
    log PROJ         - Display the contents of the given project's log file.
    log-tail PROJ    - Tail the log file of the given project.

    port-process NUM - Show information about the process that opened the given
                       TCP/IP port (may require 'sudo' on some systems).
    sqs-queues       - Show list of active local SQS queues.

EOH

function show_log_proj () {
    PROJ=$1
    cat $CMR_LOG_DIR/${PROJ}.log
}

function show_log_tail_proj () {
    PROJ=$1
    tail -f $CMR_LOG_DIR/${PROJ}.log
}

function show_port_process () {
    PORT=$1
    lsof -i :${PORT}
}

function show_sqs_queues () {
    aws --endpoint-url $CMR_SQS_ENDPOINT sqs list-queues
}
