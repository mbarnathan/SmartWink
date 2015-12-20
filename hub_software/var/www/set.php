<?php

$dev = intval(@$_REQUEST["device"]) or emsg('No device parameter');
$attr = @$_REQUEST["attr"] ?: "Level";
if (!array_key_exists("value", $_REQUEST)) {
  emsg('No value parameter');
}
$value = $_REQUEST['value'];
$escaped_value = escapeshellarg($value);

function emsg($msg) {
  echo $msg;
  exit;
}

$db = "/database/apron.db";
$dbh = new SQLite3($db);
$dbh->busyTimeout(5000);
$escaped_attr = SQLite3::escapeString($attr);
$attr_result = $dbh->query("SELECT attributeId FROM lutronAttribute WHERE description LIKE '$escaped_attr'");

$row = $attr_result->fetchArray(SQLITE3_ASSOC) or emsg("No such attribute");

$attr_id = $row["attributeId"];
$attr_result->finalize();

$device_result = $dbh->query("SELECT masterId FROM lutronDevice WHERE lNodeId = $dev");
$row = $device_result->fetchArray(SQLITE3_ASSOC) or emsg("No such device.");
$master_id = $row["masterId"];

$device_result->finalize();
$dbh->close(); 
unset($dbh);
$cmd = "aprontest -u -m $master_id -t $attr_id -v $escaped_value | sed 's/[[:space:]][[:space:]]*/ /g' | grep '^[[:space:]][[:alnum:]]*: [A-Za-z0-9\-_]*$' | sed 's/^ //'";

$output = `$cmd`;
$lines = explode(PHP_EOL, $output);

$data = array(
  "cmd" => $cmd,
  "serial" => $dev,
  "attr" => $attr,
  "value" => $value,
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

