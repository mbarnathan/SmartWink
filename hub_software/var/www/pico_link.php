<?php

// Hardware-pairs a Pico remote to a set of Caseta dimmers.
// Useful for bypassing SmartThings' considerable latency and establishing
// instant and reliable Pico-to-Caseta communication.

// Pass a negative number for the caseta ID to remove instead of add.

$pico = intval(@$_REQUEST["pico"]) or emsg('No pico parameter');
$raw_caseta = intval(@$_REQUEST["caseta"]) or emsg('No caseta parameter');

$op = ($raw_caseta >= 0) ? "lutron_pico_add" : "lutron_pico_del";
$caseta = abs($raw_caseta);

function emsg($msg) {
  echo $msg;
  exit;
}

$db = "/database/apron.db";
$dbh = new SQLite3($db);
$dbh->busyTimeout(5000);

$device_result = $dbh->query("SELECT masterId FROM lutronDevice WHERE lNodeId = $pico");
$row = $device_result->fetchArray(SQLITE3_ASSOC) or emsg("No such device.");
$pico_master = $row["masterId"];
$device_result->finalize();

$device_result = $dbh->query("SELECT masterId FROM lutronDevice WHERE lNodeId = $caseta");
$row = $device_result->fetchArray(SQLITE3_ASSOC) or emsg("No such device.");
$caseta_master = $row["masterId"];
$device_result->finalize();

$dbh->close(); 
unset($dbh);
$cmd = "aprontest --$op $caseta_master -m $pico_master | sed 's/[[:space:]][[:space:]]*/ /g' | grep '^[[:space:]][[:alnum:]]*: [A-Za-z0-9\-_]*$' | sed 's/^ //'";

$output = `$cmd`;
$lines = explode(PHP_EOL, $output);

$data = array(
  "cmd" => $cmd,
  "serial" => $pico,
  "op" => $op,
  "value" => $caseta,
);

$result = array();
foreach ($lines as $line) {
  if (!$line) { continue; }
  list($key, $value) = explode(": ", $line);
  $result[$key] = $value;
}

$data["result"] = $result;

header('Content-Type: application/json');
header('X-Response: DEVICE_EVENT');

echo json_encode($data);

?>

