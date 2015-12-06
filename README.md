# SmartWink
Use Lutron Clear Connect devices on SmartThings using a rooted Wink Hub as a bridge!

To install:

This is a two part system. You'll need both the SmartApp and the Wink Hub software. I suggest using this on a Wink Hub you don't care about, since this installation will overwrite some system files and could potentially put the hub in a bad state if something goes wrong.

The SmartApp lives in the "build" directory. Just copy the SmartApp and all device types into the SmartThings IDE like any other third-party SmartApp:

1. Go to http://graph.api.smartthings.com, "My SmartApps", "New", "From Code", paste the contents of smartwink-lutron.groovy, save, publish "for me".
2. Go to the "My Device Types" section of the IDE and create new device types using the same process copying the contents of each device type under the "build/devicetypes" directory.

Once saved and published, the SmartApp will appear as an option in the SmartThings mobile app, under "my apps".

That's half of the installation. The other half is extracting the necessary files onto your Wink Hub.

The tarball to extract to the Wink Hub is currently lives on Dropbox: https://www.dropbox.com/s/wx3tvcikfxtn5nr/smartwink.tar.gz?dl=0

You should be able to install it by SSHing into the Wink Hub and issuing "cd /; curl https://www.dropbox.com/s/wx3tvcikfxtn5nr/smartwink.tar.gz?dl=0; gunzip smartwink.tar.gz; tar xvf smartwink.tar.gz". Note that this will overwrite some files on the hub, so a backup may be a good idea.

Assuming the extraction went well with no error messages, reboot the hub (issue "reboot"), and check that everything is running:

[root@flex-dvt ~]# ps | grep smartwink
1150 root     {lutron-monitor} /bin/sh /usr/share/smartwink/lutron-monitor -
1169 root     {discovery-serve} /bin/sh /usr/share/smartwink/discovery-server
1171 root     socat -u UDP4-RECVFROM:1900,ip-add-membership=239.255.255.250:wlan0,fork,ip-pktinfo,reuseaddr SYSTEM:grep -o -E 'urn:schemas-smartwink:device:SmartWink:1\\|ssdp:all\\|ssdp:discover' | xargs -t -n 1 -r /usr/share/smartwink/discovery-respond $SOCAT_PEERADDR $SOCAT_PEERPORT $SOCAT_IP_LOCADDR 1900 $SOCAT_IP_IF
1174 root     {subscribe-liste} /bin/bash /usr/share/smartwink/subscribe-listen
1176 root     socat TCP-listen:1081,fork,reuseaddr,crnl EXEC:php /usr/share/smartwink/subscribe.php
1217 root     {apron-wrapper} /bin/sh /usr/share/smartwink/apron-wrapper
1222 root     {apron-monitor} /bin/sh /usr/share/smartwink/apron-monitor -

[root@flex-dvt ~]# curl http://localhost/lutron.php
[{"id":9,"serial":11111111,"name":"Caseta Plug-in Dimmer","type":"PID_DIMMER"},
{"id":10,"serial":22222222,"name":"Caseta Wall Dimmer","type":"WALL_DIMMER"},
{"id":11,"serial":33333333,"name":"3 Button Pico with Raise\/Lower","type":"PICO"}]

(Your output will differ, and will be an empty array if you have nothing paired - but the important thing is that you can connect and don't get a 404).

With those sanity checks out of the way, start up the app and give it a try! It should automatically discover the hub, and once selected, will discover your existing Lutron devices and allow you to pair new ones.

Known issues:

1. Button press events from Pico remotes are usually delivered in one serial read. Occasionally (~10%) they're split across two reads, and these presses will not be detected yet.
2. Devices first install in the "on" state. The device may need to be toggled before it picks up the correct state.
3. I've sometimes seen it take a minute or two to first install a device. I think the write to /database/smartwink_subscriptions is being buffered, if anyone wants to investigate.
4. I only have the v1 SmartThings hub, so I have no idea if any of this works with the SmartThings v2 hub.
5. The Wink Hub piece needs a proper installer, or firmware. I didn't package it in this way because I didn't want to take the chance of bricking my only hub, or running afoul of Wink for redistributing their software.
