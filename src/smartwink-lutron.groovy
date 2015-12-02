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
    log.trace "SmartWink installed with settings: ${settings}"
    state.inDeviceDiscovery = false
    state.pairingMode = false
    state.pairStatus = null as Boolean

    removeHubsExcept(selectedHubs)  // Will also remove child devices on removed hubs
    populateHubs(selectedHubs)

    removeDevicesExcept(selectedDevices)
    populateDevices(selectedDevices)

    initialize()
}

def uninstalled() {
    removeHubsExcept([])
    log.trace "SmartWink uninstalled"
}

def updated() {
    log.trace "SmartWink updated with settings: ${settings}"
    unschedule()
    initialize()
}

def initialize() {
    log.debug "Initializing SmartWink"
    state.deviceQueue = [:]
    unsubscribe()
    subscribe(location, null, onLocation, [filterEvents:false])
}

// <editor-fold desc="Hub Discovery - returns map selectedHubs [mac: name]">

def hubDiscovery() {
    def refreshInterval = 3

    state.foundHubs = state.foundHubs ?: [:]
    state.hubRefreshes = (state.hubRefreshes ?: 0) + 1

    if (state.hubRefreshes == 1) {
        subscribe(location, null, onLocation, [filterEvents:false])
    }

    if (state.hubRefreshes % 5 == 1) {
        startHubDiscovery()
    }

    dynamicPage(name:"hubDiscovery", title:"Hub Discovery Started!", nextPage:"deviceDiscovery", refreshInterval:refreshInterval, install:false, uninstall: true) {
        section("Please wait while we discover your Wink Hubs. This could take a few minutes. Select your device below once discovered.") {
            // TODO: Better pairing support for multiple:true.
            input "selectedHubs", "enum", required:true, title:"Select Wink Hub (${state.foundHubs.size()} found)", multiple:true, options: state.foundHubs
        }
    }
}

def startHubDiscovery() {
    sendHubCommand(new physicalgraph.device.HubAction("lan discovery urn:schemas-smartwink:device:SmartWink:1", physicalgraph.device.Protocol.LAN))
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
                    log.debug "Got JSON data from Wink Hub (${mac}): ${json}"
                    state.foundDevices[mac] = json
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
                    log.info "Ready to pair to Wink Hub"
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
        throw new IllegalArgumentException("SmartWink SSDP discovery event doesn't seem to have a MAC address.")
    }
    def foundHubs = state.foundHubs
    def newIp = convertHexToIP(parsedEvent.networkAddress)
    def lastIp = foundHubs.put(parsedEvent.mac, newIp)
    if (lastIp) {
        updateHubIp(parsedEvent.mac, lastIp, newIp)
    }
}

private updateHubIp(mac, oldIp, newIp) {
    if (oldIp == newIp) {
        return
    }

    def hub = getChildDevice(mac)
    if (hub) {
        log.info "Wink Hub (${mac}) IP changed: ${oldIp} to ${newIp}. Updating."
        hub.sendEvent(name:"networkAddress", value: newIp)
        hub.updateDataValue("networkAddress", newIp)
    }
}

def removeHubsExcept(keptMacs) {
    def toRemove = getChildDevices().findAll { !keptMacs.contains(it.deviceNetworkId) }
    log.info "Removing ${toRemove.size()} de-selected Wink Hubs (and all child devices). Keeping ${keptMacs}."
    toRemove.each { hub ->
        log.debug "Removing Wink Hub ${hub.deviceNetworkId}"
        deleteChildDevice(hub.deviceNetworkId)
    }

    state.foundHubs.keySet().retainAll(keptMacs)
}

def populateHubs(hubs) {
    log.info "Creating new SmartThings devices for Wink Hubs: ${hubs}"
    return hubs.collect({ mac ->
        def ip = state.foundHubs[mac]
        asHub(mac, ip)
    })
}

def asHub(hubMac, hubAddr) {
    log.debug "Realizing hub with MAC: ${hubMac}, IP: ${hubAddr}"

    def hubDevice = getChildDevice(hubMac)
    def oldIp = null
    if (hubDevice) {
        log.info("Found existing hub device with MAC ${hubMac}, updating IP if necessary.")
        oldIp = hubDevice.getDataValue("networkAddress")
    } else {
        log.info("Creating new hub device for MAC ${hubMac}")
        hubDevice = addChildDevice("smartwink", "Wink Hub", hubMac, location.hubs[0].id, [name: "Wink Hub", completedSetup: true])
    }

    updateHubIp(hubMac, oldIp, hubAddr)
    return hubDevice
}

// </editor-fold>

// <editor-fold desc="Device Discovery">

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

def deviceDiscovery() {
    def refreshInterval = 3

    state.inDeviceDiscovery = true
    state.pairingMode = false
    state.foundDevices = state.foundDevices ?: [:]

    state.deviceRefreshes = (state.deviceRefreshes ?: 0) + 1

    if (state.deviceRefreshes % 5 == 1) {
        selectedHubs.each { mac -> discoverLutronDevices(mac) }
    }

    def deviceMap = [:]
    state.foundDevices.each { hub, json ->
        json.each { device ->
            deviceMap[device.serial] = "${device.name} (${device.serial})"
        }
    }

    log.info("Discovered devices: ${state.foundDevices}, flattened to ${deviceMap}")

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
def populateDevices(selected) {
    def hubToJson = state.foundDevices
    log.info "Creating SmartThings device nodes for ${hubToJson.size()} Wink Hubs."
    log.info "${selected.size()} devices are selected by the user."
    return hubToJson.collect({ hubMac, jsonArray ->
        def hubDevices = jsonArray \
                .findAll { selected.containsKey(it.serial) } \
                .collect({ json -> asDevice(hubMac, json) })

        log.info "Created ${hubDevices.size()} devices for Wink Hub ${hubMac}"
        hubDevices
    })
}

def asDevice(jsonDevice, hubMac) {
    log.info "Found device ${jsonDevice}!"
    def device = getChildDevice("${jsonDevice.serial}")
    if (!device) {
        log.info "Device ${jsonDevice} is new! Adding to devices."
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

def removeDevicesExcept(keptSerials) {
    def toRemove = getChildDevices().findAll { !keptSerials.contains(it.deviceNetworkId) }
    log.info "Removing ${toRemove.size()} de-selected devices. Keeping: ${keptSerials}"
    toRemove.each {
        log.debug "Removing device ${it.deviceNetworkId}"
        deleteChildDevice(it.deviceNetworkId)
    }

    state.foundDevices.removeAll { json-> !keptSerials.contains(json.serial) }
}

def discoverLutronDevices(hubMac) {
    def netAddr = state.foundHubs[hubMac]
    if (!netAddr) {
        throw new IllegalArgumentException("Tried to add a device to a nonexistent hub: no hub found with mac ${hubMac}!")
    }
    netAddr += ":80"

    log.info("Discovering devices on ${hubMac}: GET http://${netAddr}/lutron.php. Device discovery mode? ${state.inDeviceDiscovery}")
    sendHubCommand(new physicalgraph.device.HubAction([
            method: "GET",
            path: "/lutron.php",
            headers: [
                    HOST: netAddr
            ]], "${hubMac}"))
}

// </editor-fold>

// TODO: m4

private String convertHexToIP(hex) {
    [convertHexToInt(hex[0..1]),convertHexToInt(hex[2..3]),convertHexToInt(hex[4..5]),convertHexToInt(hex[6..7])].join(".")
}

private Integer convertHexToInt(hex) {
    Integer.parseInt(hex,16)
}
