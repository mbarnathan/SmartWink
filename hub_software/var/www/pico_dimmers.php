<?php

// Hardware-pairs Pico remotes to a set of Caseta dimmers.
// Useful for bypassing SmartThings' considerable latency and establishing
// instant and reliable Pico-to-Caseta communication.

// Pass comma delimited Pico serials in the pico parameter.
if (!isset($_REQUEST["pico"])) {
  emsg('No pico parameter');
}

// And a comma delimited set of Caseta serials as the caseta parameter.
if (!isset($_REQUEST["caseta"])) {
  emsg("No caseta parameter specified (even empty).");
}

header('Content-Type: application/json');
header('X-Response: DEVICE_LINKED');

$picos = $_REQUEST["pico"] ? array_map("intval", explode(",", $_REQUEST["pico"])) : array();
$new_dimmers = $_REQUEST["caseta"] ? array_map("intval", explode(",", $_REQUEST["caseta"])) : array();

function emsg($msg) {
  echo $msg;
  exit;
}

// Get existing links.
$existing_dimmers = array();
foreach ($picos as $pico) {
  $existing_dimmers[$pico] = array();
}

$db = "/database/lutron-db.sqlite";
$dbh = new SQLite3($db);
$dbh->busyTimeout(15000);

$picos_str = implode(",", $picos);
$query = "SELECT DISTINCT GetPresetComponentOwner.SerialNumber AS Pico, GetAssignableObjects.SerialNumber AS Caseta FROM GetPresetComponentOwner,PresetAssignment,GetAssignableObjects WHERE PresetAssignment.PresetID=GetPresetComponentOwner.PresetID AND GetAssignableObjects.AssignableObjectID=PresetAssignment.AssignableObjectID AND GetPresetComponentOwner.SerialNumber IN ($picos_str)";
$device_result = $dbh->query($query);
while ($row = $device_result->fetchArray(SQLITE3_ASSOC)) {
  $existing_dimmers[$row["Pico"]][] = $row["Caseta"];
}

$device_result->finalize();
$dbh->close();

$to_add = array();
$to_remove = array();
$to_lookup = array();

foreach ($picos as $pico) {
  $to_add[$pico] = array_diff($new_dimmers, $existing_dimmers[$pico]);
  $to_remove[$pico] = array_diff($existing_dimmers[$pico], $new_dimmers);
  $to_lookup = array_merge($to_lookup, $to_add[$pico], $to_remove[$pico]);
  $to_lookup[] = $pico;
}

$to_lookup = array_unique($to_lookup);

// Look up master IDs.

$db = "/database/apron.db";
if (!file_exists($db)) {
  $db = "/var/lib/database/apron.db";
}

$dbh = new SQLite3($db);
$dbh->busyTimeout(15000);

function query_masters($dbh, $list, &$master_array) {
  $list_str = implode(",", $list);
  $device_result = $dbh->query("SELECT lNodeId, masterId FROM lutronDevice WHERE lNodeId IN ($list_str)");
  while ($row = $device_result->fetchArray(SQLITE3_ASSOC)) {
    $master_array[$row["lNodeId"]] = $row["masterId"];
  }
  $device_result->finalize(); 
}

$masters = array();
query_masters($dbh, $to_lookup, $masters);

$dbh->close(); 
unset($dbh);

$results = array();
$added = array();
$removed = array();

foreach ($picos as $pico) {
  $added[$pico] = array();
  $removed[$pico] = array();
  $results[$pico] = array();
  $pico_master = $masters[$pico];
  foreach ($to_add[$pico] as $caseta_serial) {
    $caseta_master = $masters[$caseta_serial];
    $results[$pico][$caseta_serial] = cmd("add", $caseta_master, $pico_master);
    if ($results[$pico][$caseta_serial]["result"]["Status"] == "FLX_OK") {
      $added[$pico][] = $caseta_serial;
    }
  }

  foreach ($to_remove[$pico] as $caseta_serial) {
    $caseta_master = $masters[$caseta_serial];
    $results[$pico][$caseta_serial] = cmd("del", $caseta_master, $pico_master);
    if ($results[$pico][$caseta_serial]["result"]["Status"] == "FLX_OK") {
      $removed[$pico][] = $caseta_serial;
    }
  }

  $results[$pico]["added"] = $added[$pico];
  $results[$pico]["removed"] = $removed[$pico];
}

echo json_encode($results);

function cmd($op, $caseta, $pico_master) {
  $apron = "aprontest --lutron_pico_$op $caseta -m $pico_master | sed 's/[[:space:]][[:space:]]*/ /g' | grep '^[[:space:]][[:alnum:]]*: [A-Za-z0-9\-_]*$' | sed 's/^ //'";
  $output = `$apron`;
  $lines = explode(PHP_EOL, $output);

  $data = array(
    "cmd" => $apron,
    "op" => $op,
    "output" => $output,
  );

  $result = array();
  foreach ($lines as $line) {
    if (!$line) { continue; }
    list($key, $value) = explode(": ", $line);
    $result[$key] = $value;
  }

  $data["result"] = $result;
  return $data;
}

?>

