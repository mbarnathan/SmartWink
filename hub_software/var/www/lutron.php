<?php

$db = "/database/lutron-db.sqlite";
$dbh = new SQLite3($db);
$devs = $dbh->query("SELECT DeviceID AS id, SerialNumber AS serial, Description AS name, XMLDescription AS type FROM Device LEFT JOIN DeviceInfo ON Device.DeviceInfoID = DeviceInfo.DeviceInfoID WHERE type NOT LIKE 'CASETA_GATEWAY'");

$data = array();
while ($row = $devs->fetchArray(SQLITE3_ASSOC)) {
  $data[] = $row;
}

$dbh->close();
header('Content-Type: application/json');
header('X-Response: DEVICE_DISCOVERY');
echo json_encode($data);

?>

