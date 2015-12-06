<?php

header('Content-type: text/plain');

$callback_address = @$_REQUEST["callback"] or die("No callback address");

# The buffering behavior of this hub makes using popen/proc_open harder 
# than simply wrapping a shell script.
$escaped_callback = escapeshellarg($callback_address);
system("/usr/share/smartwink/apronpair $escaped_callback", $return_code);

if ($return_code != 0) {
  echo "Command exited with return code $return_code";
}

?>
