<?php

$subscription_file = "/database/smartwink-subscriptions";

# Barebones upnp subscribe / unsubscribe implementation.
# PHP on the Wink Hub doesn't come with sockets support, so we tie to socat.
$request = stream_get_line(STDIN, 4096, "\r\n\r\n");
$events = array();
$callbacks = array();
if (preg_match("@SUBSCRIBE /upnp/event/(\w+)/(\w+)@", $request, $events) &&
    preg_match("@CALLBACK: <([^\s>]+)>@", $request, $callbacks)) {
    subscribe($events[1], $events[2], $callbacks[1]);
} elseif (preg_match("@SUBSCRIBE /upnp/event/(\w+)/(\w+)@", $request, $events) &&
    preg_match("@SID: (\w+)@", $request, $sid)) {
    unsubscribe($events[1], $events[2], $sid[1]);
}

fclose(STDOUT);
fclose(STDIN);

function subscribe($publisher, $path, $callback) {
    global $subscription_file;

    $key = "$publisher $path";
    $id = md5($key);
    $subscriptions = file($subscription_file) ?: array();
    $subscriptions = array_map(rtrim, $subscriptions);
    array_push($subscriptions, "$key $callback");
    $subscriptions = array_unique($subscriptions);
    file_put_contents($subscription_file, join("\n", $subscriptions));
    respond($id);
}

function unsubscribe($publisher, $path, $sid) {
    global $subscription_file;

    $record = "$publisher $path";
    $quoted_record = preg_quote($record, "/");
    $subscriptions = file($subscription_file);
    $remaining = preg_grep("/$quoted_record/", $subscriptions, PREG_GREP_INVERT);
    $remaining = array_map(rtrim, $remaining);
    if ($sid == md5($record) && count($remaining) < count($subscriptions)) {
        file_put_contents($subscription_file, join("\n", $remaining));
	echo "HTTP/1.1 200 OK\n\n";
    } else {
        echo "HTTP/1.1 412 Precondition Failed\n\n";
    }
}

function respond($sid) {
    $response = <<<EOF
HTTP/1.1 200 OK
SERVER: Linux/2.6 UPnP/1.1 Wink/1.0
SID: uuid:$sid
TIMEOUT: Second-999999999
CONTENT_LENGTH: 0

EOF;

    echo $response;
}
