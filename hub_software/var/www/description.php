<?php
header('Content-type: text/xml');
?>
<?xml version="1.0" encoding="UTF-8" ?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
<specVersion>
<major>1</major>
<minor>0</minor>
</specVersion>
<URLBase>http://<?= $_SERVER['SERVER_ADDR'] . ":" . $_SERVER['SERVER_PORT'] . "/"; ?></URLBase>
<device>
<deviceType>urn:schemas-smartwink:device:SmartWink:1</deviceType>
<friendlyName>SmartWink (<?= $_SERVER['SERVER_ADDR'] ?>)</friendlyName>
<manufacturer>SmartWink</manufacturer>
<manufacturerURL>http://www.github.com/quantiletree/SmartWink</manufacturerURL>
<modelDescription>SmartWink SmartThings to Wink Bridge</modelDescription>
<modelName>SmartWink 2015</modelName>
<modelNumber>1</modelNumber>
<serialNumber><?= $_REQUEST["mac"] ?></serialNumber>
<UDN>uuid:<?= $_REQUEST["udn"] ?></UDN>
</device>
</root>

