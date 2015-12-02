/**
 *  SmartWink
 *
 *  Bidirectional SmartThings to Wink bridge for Lutron devices. Requires rooting and modifying the Wink Hub.
 *
 *  Author: Michael Barnathan (michael@barnathan.name)
 */

definition(
        name: "SmartWink - Lutron Bridge",
        namespace: "smartwink",
        author: "Michael Barnathan",
        description: "Links a rooted Wink Hub running the SmartWink service to SmartThings. Allows use of Pico Remotes and Caseta dimmers from within SmartThings.",
        category: "SmartThings Labs",
        iconUrl: "https://s3.amazonaws.com/smartapp-icons/Partner/hue.png",
        iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Partner/hue@2x.png")

preferences {
    page(name: "hubDiscovery", title:"Hub Discovery", nextPage: "deviceDiscovery")
    page(name: "deviceDiscovery", title:"Lutron Device Discovery")
    page(name: "pairing", title:"Pairing Started!")
}

def installed() {
    log.trace "SmartWink Installed with settings: ${settings}"
    state.inDeviceDiscovery = false
    state.pairingMode = false
    state.pairStatus = null as Boolean
    initialize()
}

def uninstalled() {
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
    log.trace "SmartWink uninstalled"
}

def updated() {
    log.trace "SmartWink updated with settings: ${settings}"
    unschedule()
    initialize()
}

def initialize() {
    log.debug "Initializing SmartWink"
    unsubscribe()
    subscribe(location, null, onLocation, [filterEvents:false])
}

// <editor-fold desc="Hub Discovery - returns map selectedHubs [mac: name]">

def hubDiscovery() {
    def refreshInterval = 3

    state.hubMacToIp = state.hubMacToIp ?: []
    state.hubRefreshes = (state.hubRefreshes ?: 0) + 1

    if (state.hubRefreshes == 1) {
        subscribe(location, null, onLocation, [filterEvents:false])
    }

    if (state.hubRefreshes % 5 == 1) {
        startDiscovery()
    }

    dynamicPage(name:"hubDiscovery", title:"Hub Discovery Started!", nextPage:"deviceDiscovery", refreshInterval:refreshInterval, install:false, uninstall: true) {
        section("Please wait while we discover your Wink Hubs. This could take a few minutes. Select your device below once discovered.") {
            // TODO: Better pairing support for multiple:true.
            input "selectedHubs", "enum", required:true, title:"Select Wink Hub (${state.hubMacToIp.size()} found)", multiple:true, options: state.hubMacToIp
        }
    }
}

def startDiscovery() {
    sendHubCommand(new physicalgraph.device.HubAction("lan discovery urn:schemas-smartwink:device:SmartWink:1", physicalgraph.device.Protocol.LAN))
    //state.hubMacToIp = [
    //        "34:23:BA:EC:17:52": "10_3_0_5:1080"
    //]
    //populateHubs(state.hubMacToIp.keySet())
}

def onLocation(evt) {
    def description = evt.description
    log.trace "SmartWink Location Event: ${description}"

    def parsedEvent = parseLanMessage(description)
    def rawMac = parsedEvent.mac
    def mac = ""
    rawMac.eachWithIndex { macChar, macIndex ->
        if (macIndex % 2 == 0 && macIndex != 0) {
            mac += ":"
        }
        mac += macChar
    }

    // Avoid the mistake the Hue hub makes - check for a specific SmartWink device type rather than Basic.
    if (parsedEvent?.ssdpTerm?.contains("urn:schemas-smartwink:device:SmartWink:1")) {
        discoveredHub(parsedEvent)
    } else if (parsedEvent.headers && parsedEvent.body && parsedEvent.headers["Content-Type"]?.contains("json")) {
        def responseType = parsedEvent.headers["X-Response"]
        log.trace "Got JSON response (X-Response: ${responseType}) from Wink Hub: ${parsedEvent.body}"

        def json = new groovy.json.JsonSlurper().parseText(parsedEvent.body)
        log.trace "Parsed JSON into ${json}"

        switch (responseType ?: "") {
            case "DEVICE_DISCOVERY":
                if (state.inDeviceDiscovery) {
                    populateDevices(mac, json)
                } else {
                    log.info "SmartWink received device discovery results, but not in discovery mode. Ignoring."
                }
                break
            case "DEVICE_PAIRED":
                def status = json.status ?: "unknown"
                log.info "Got pairing response from Wink Hub: ${status}"
                if (state.pairingMode) {
                    if (status == "ready") {
                        log.warn "Ready to pair status when already in pairing mode? Ignoring."
                    } else {
                        state.pairStatus = (status == "FLX_OK") as Boolean
                        state.pairingMode = false
                        log.info "Wink Hub pairing completed, status ${state.pairStatus}."
                    }
                } else if (status == "ready") {
                    log.info "Ready to pair Wink Hub"
                    state.pairingMode = true
                }
                break
            case "DEVICE_EVENT":
                // These events are supposed to fire parse(), but because that relies on the DNI being the MAC address,
                // and since these events are all coming from the same MAC, we must handle it here instead.
                return dispatchDeviceEvent(parsedEvent, mac, json)
            case "":
                log.debug "Empty X-Response, probably not a SmartWink message. Ignoring."
                break
            default:
                log.warn "Unknown X-Response ${responseType} (SmartApp and Wink Hub on different SmartWink versions?), ignoring."
                break
        }
    }
    return parsedEvent
}

private discoveredHub(parsedEvent) {
    // Found a hub. Check if it already exists, and add it if not.
    log.info "Found SmartWink Hub via SSDP: ${parsedEvent}"
    if (!parsedEvent?.mac) {
        log.error "SmartWink SSDP discovery event doesn't seem to have a MAC address."
    }
    def hubs = state.hubMacToIp
    def newIp = convertHexToIP(parsedEvent.networkAddress)
    def lastIp = hubs.put(parsedEvent.mac, newIp)
    if (lastIp) {
        updateHubIp(parsedEvent.mac, lastIp, newIp)
    }
}

private updateHubIp(mac, oldIp, newIp) {
    log.info "Wink Hub (${mac}) IP changed: ${oldIp} to ${newIp}. Updating child devices."
    def hub = getChildDevice(mac)
    if (!hub) {
        log.info "Couldn't find hub device, likely still in discovery mode."
        return
    }


}

private dispatchDeviceEvent(parsedEvent, hub, json) {
    if (!json.serial) {
        log.warn "No device serial in device dispatch JSON message. Ignoring."
        return
    }

    def device = getChildDevice("${json.serial}")
    if (!device) {
        log.warn "Couldn't find device for ${json.serial}. Ignoring device event."
        return
    }

    device.handleEvent(parsedEvent, hub, json)
}

def populateHubs(hubMacs) {
    return hubMacs.collect({ asHub(it) })
}

def getHubAddress(hubSerial) {
    return hubSerial.replaceAll("_", ".")
}

def asHub(hubMac) {
    def hubSerial = state.hubMacToIp[hubMac]
    log.debug "Hub MAC: ${hubMac}, Serial: ${hubSerial}"

    def newHub = getChildDevice(hubMac)
    if (newHub) {
        log.info("Found existing hub device with MAC ${hubMac}")
    } else {
        log.info("Creating new hub device with MAC ${hubMac}")
        newHub = addChildDevice("smartwink", "Wink Hub", hubMac, location.hubs[0].id, [name: "Wink Hub", completedSetup: true])
    }
    def ip = getHubAddress(hubSerial)
    newHub.sendEvent(name:"networkAddress", value: ip)
    newHub.updateDataValue("networkAddress", ip)
    return newHub
}

// </editor-fold>

// <editor-fold desc="Device Discovery">

def deviceDiscovery() {
    def refreshInterval = 3

    state.inDeviceDiscovery = true
    state.pairingMode = false
    state.discoveredDevices = state.discoveredDevices ?: [:]

    state.deviceRefreshes = (state.deviceRefreshes ?: 0) + 1

    if (state.deviceRefreshes % 5 == 1) {
        selectedHubs.each { discoverLutronDevices(getChildDevice(it)) }
    }

    def deviceMap = [:]
    state.discoveredDevices.each { hub, deviceSerials ->
        deviceSerials.each { deviceSerial ->
            def device = getChildDevice("${deviceSerial}")
            if (device) {
                deviceMap[deviceSerial] = "${device.name} (${deviceSerial})"
            } else {
                log.error "Couldn't find device corresponding to ${deviceSerial}"
            }
        }
    }

    log.info("Discovered devices: ${state.discoveredDevices}, flattened to ${deviceMap}")

    dynamicPage(name:"deviceDiscovery", title:"Device Discovery Started!", nextPage:"deviceDiscovery", refreshInterval:refreshInterval, install:true, uninstall: false) {
        section("Lutron Devices:") {
            input "selectedDevices", "enum", required:true, title:"Select devices to manage (${deviceMap.size()} found)", multiple:true, options: deviceMap
        }

        section("Pair Device:") {
            href(name: "hrefPair", title: "Pair new device...", description: "Tap to start pairing", required: false, page: pairing)
        }
    }
}

def pairing() {
    def refreshInterval = 3

    state.pairRefreshes = (state.pairRefreshes ?: 0) + 1

    if (state.pairStatus != true && state.pairRefreshes % 10 == 1 && !state.pairingMode) {
        startPairing(getChildDevice(selectedHubs[0]))
    }

    def message
    if (state.pairStatus == true) {
        message = "Device paired successfully! You can close this dialog and return to device discovery."
    } else if (state.pairStatus == false && !state.pairingMode) {
        message = "Pairing failed. Try again in a moment."
    } else if (!state.pairingMode) {
        message = "Please wait while we put your hub in pairing mode..."
    } else {
        message = "Your Wink Hub is ready to pair. Hold the off button on your new device until the light on the device blinks rapidly."
    }
    dynamicPage(name:"pairing", title:"Pairing Started!", refreshInterval:refreshInterval, nextPage:"deviceDiscovery", install:false, uninstall: false) {
        section("Pair new device...") {
            paragraph message
        }
    }
}

private getCallBackAddress() {
    def hub = location.hubs[0]
    return "${hub.localIP}:${hub.localSrvPortTCP}"
}

def startPairing(hub) {
    def netAddr = hub.getDeviceDataByName("networkAddress") ?: hub.latestState('networkAddress')?.stringValue
    def dni = hub.deviceNetworkId

    def callback = "http://${getCallBackAddress()}/notify/paired"
    log.info("Sending pairing request to Wink Hub ${dni}: POST http://${netAddr}/pair.php. Callback on ${callback}")

    sendHubCommand(new physicalgraph.device.HubAction([
            method: "POST",
            path: "/pair.php",
            headers: [
                    HOST: netAddr,
                    "Content-Length": 0,
            ],
            query: [callback: callback]
    ], "${dni}"))
}

/* Example schema:
	[
	  "device": {
		"id": "1", // Wink hub assigned ID, which can change
		"serial": "8675309", // Lutron ID, which is tied to the device
		"name": "Device Name",
		"type": "Pico Remote"
	  },
	  ...
	]
*/
def populateDevices(hub, response) {
    log.debug "Got JSON data from Wink Hub (${hub}): ${response}"
    state.discoveredDevices[hub] = response.collect({
        asDevice(it, hub)
        it.serial
    })
}

def asDevice(jsonDevice, hub) {
    log.info "Found device ${jsonDevice}!"
    def device = getChildDevice("${jsonDevice.serial}")
    if (!device) {
        log.info "Device ${jsonDevice} is new! Adding to devices."
        def winkHub = getChildDevice(hub)
        def hubAddr = winkHub.getDataValue("networkAddress")
        device = addChildDevice("smartwink", "${jsonDevice.type}", "${jsonDevice.serial}", location.hubs[0].id,
                                [name: jsonDevice.name, completedSetup: true])
        device.sendEvent(name:"hubAddress", value: hubAddr)
        device.updateDataValue("hubAddress", hubAddr)
        device.refresh()
        log.info "Successfully added ${jsonDevice} to devices."
    }
    return device
}

def discoverLutronDevices(hub) {
    def netAddr = hub.getDeviceDataByName("networkAddress") ?: hub.latestState('networkAddress')?.stringValue
    def dni = hub.deviceNetworkId

    log.info("Discovering devices on ${dni}: GET http://${netAddr}/lutron.php. Device discovery mode? ${state.inDeviceDiscovery}")

    sendHubCommand(new physicalgraph.device.HubAction([
            method: "GET",
            path: "/lutron.php",
            headers: [
                    HOST: netAddr
            ]], "${dni}"))
}

// </editor-fold>

// TODO: m4

private String convertHexToIP(hex) {
    [convertHexToInt(hex[0..1]),convertHexToInt(hex[2..3]),convertHexToInt(hex[4..5]),convertHexToInt(hex[6..7])].join(".")
}

private Integer convertHexToInt(hex) {
    Integer.parseInt(hex,16)
}
