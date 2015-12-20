/**dnl
include(`app_constants.m4')dnl
define(`__name__', __app__)dnl
define(`__human_name__', __app__)dnl
define(`__description__', `Links a rooted Wink Hub running the __app__ service to SmartThings. Allows use of Pico Remotes and Caseta dimmers from within SmartThings.')dnl
define(`__file_description__', __description__)dnl
define(`__author__', `Michael Barnathan')dnl
define(`__author_email__', `michael@barnathan.name')dnl
define(`__icon__', `http://cdn.device-icons.smartthings.com/Outdoor/outdoor18-icn.png')dnl
define(`__icon2x__', `http://cdn.device-icons.smartthings.com/Outdoor/outdoor18-icn@2x.png')dnl
include(`header.m4')
 */

definition(
        // include(`definition.m4')
)

preferences {
    page(name: "hubDiscovery", title:"Hub Discovery", nextPage: "deviceDiscovery")
    page(name: "deviceDiscovery", title:"Lutron Device Discovery")
    page(name: "pairing", title:"Pairing Started!")
}

def installed() {
    log.trace "__name__ installed with settings: ${settings}"
    initialize()
}

def uninstalled() {
    removeDevicesExcept([])
    log.trace "__name__ uninstalled"
}

def updated() {
    log.trace "__name__ updated with settings: ${settings}"

    state.hubRefreshes = 0
    state.deviceRefreshes = 0
    state.pairRefreshes = 0
    state.inDeviceDiscovery = false
    state.pairingMode = false
    state.pairStatus = null as Boolean

    removeDevicesExcept(selectedHubs + selectedDevices)
    populateDevices(selectedDevices)

    initialize()
}

def initialize() {
    log.debug "Initializing __name__"
    unschedule()
    unsubscribe()
    subscribe(location, null, onLocation, [filterEvents:false])
    runEvery10Minutes("startHubDiscovery")
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
    // This SSDP type must be kept synced between the hub and the app, or the hub will not be discovered.
    sendHubCommand(new physicalgraph.device.HubAction("lan discovery urn:schemas-smartwink:device:SmartWink:1", physicalgraph.device.Protocol.LAN))
}

def onLocation(evt) {
    def description = evt.description

    def parsedEvent = parseLanMessage(description)
    def rawMac = parsedEvent.mac
    def mac = ""
    rawMac.eachWithIndex { macChar, macIndex ->
        if (macIndex % 2 == 0 && macIndex != 0) {
            mac += ":"
        }
        mac += macChar
    }

    // Avoid the mistake the Hue Connect app makes - check for a specific SmartWink device type rather than Basic.
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
                    log.info "__name__ received device discovery results, but not in discovery mode. Ignoring."
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
            case "DEVICE_LINKED":
                // Hard pairing or unpairing completed.
                json.each { serial, data ->
                    def controller = getChildDevice("${serial}")
                    if (!controller) {
                        log.warn "Pairing event from unknown __app__ device: ${json.serial}. Full response: ${data}"
                    } else {
                        def added = data.added.collect { getChildDevice("${it}")?.displayName }.join(", ")
                        def removed = data.removed.collect { getChildDevice("${it}")?.displayName }.join(", ")
                        def msg = "${controller.displayName} hard-paired: added [${added}], removed [${removed}]"
                        log.info msg
                        sendNotificationEvent(msg)
                    }
                }
                break
            case "":
                log.debug "Empty X-Response, probably not a __name__ message. Ignoring."
                break
            default:
                log.warn "Unknown X-Response ${responseType} (SmartApp and Wink Hub on different __app__ versions?), ignoring."
                break
        }
    }
    return parsedEvent
}

private discoveredHub(parsedEvent) {
    // Found a hub. Check if it already exists, and add it if not.
    log.info "Found __name__ Hub via SSDP: ${parsedEvent}"
    if (!parsedEvent?.mac) {
        throw new IllegalArgumentException("__name__ SSDP discovery event doesn't seem to have a MAC address.")
    }
    def foundHubs = state.foundHubs
    def newIp = convertHexToIP(parsedEvent.networkAddress)
    def lastIp = foundHubs.put(parsedEvent.mac, newIp)
    if (lastIp && lastIp != newIp) {
        log.info "Wink Hub (${parsedEvent.mac}) IP changed: ${lastIp} to ${newIp}. Updating."
    }
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
    state.pairStatus = null as Boolean
    state.foundDevices = state.foundDevices ?: [:]

    state.deviceRefreshes = (state.deviceRefreshes ?: 0) + 1

    if (state.deviceRefreshes % 5 == 1) {
        selectedHubs.each { mac -> requestDevices(mac) }
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
        startPairing(selectedHubs[0])
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

def getCallBackAddress() {
    def hub = getSmartThingsHub()
    return "${hub.localIP}:${hub.localSrvPortTCP}"
}

def startPairing(hubMac) {
    def netAddr = state.foundHubs[hubMac]
    if (!netAddr) {
        throw new IllegalArgumentException("No Wink Hub to IP mapping for MAC ${hubMac}!")
    }

    def callback = "http://${getCallBackAddress()}/notify/paired"
    log.info("Sending pairing request to Wink Hub ${hubMac}: POST http://${netAddr}/pair.php. Callback on ${callback}")

    sendHubCommand(new physicalgraph.device.HubAction(
            method: "POST",
            path: "/pair.php",
            headers: [
                    HOST: "${netAddr}:80",
                    "Content-Length": 0,
            ],
            query: [callback: callback]
    ))
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
    log.info "Creating SmartThings device nodes for ${selected.size()} Lutron devices on ${hubToJson.size()} Wink Hubs."
    return hubToJson.collect({ hubMac, jsonArray ->
        def hubDevices = jsonArray.findAll { selected.contains(it.serial as String) }.collect({ json -> asDevice(hubMac, json) })
        log.info "Created ${hubDevices.size()} devices under Wink Hub ${hubMac}"
        hubDevices
    })
}

private asDevice(hubMac, jsonDevice) {
    log.debug "Creating or referencing ${jsonDevice}."
    def device = getChildDevice("${jsonDevice.serial}")

    if (!device) {
        device = addChildDevice("__namespace__", "${jsonDevice.type}", "${jsonDevice.serial}", getSmartThingsHub().id,
                                [name: jsonDevice.name, completedSetup: true])

        // Instead of the questionable practice of storing the IP address directly, store the MAC and look up the IP
        // from this SmartApp directly. This ensures that when the hub's IP changes, the devices remain accessible.
        device.sendEvent(name:"hubMac", value: hubMac)
        device.updateDataValue("hubMac", hubMac)
        device.refresh()
        log.info "Successfully added ${jsonDevice} to devices."
    } else {
        log.info "Device ${jsonDevice} already exists, using the existing instance."
    }
    return device
}

def removeDevicesExcept(keptDnis) {
    def toRemove = getChildDevices().findAll { !keptDnis.contains(it.deviceNetworkId) }
    log.info "Removing ${toRemove.size()} de-selected devices. Keeping: ${keptDnis}"
    toRemove.each {
        log.debug "Removing device ${it.deviceNetworkId}"
        deleteChildDevice(it.deviceNetworkId)
    }

    state.foundHubs.keySet().retainAll(keptDnis)
}

def requestDevices(hubMac) {
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

private String convertHexToIP(hex) {
    [convertHexToInt(hex[0..1]),convertHexToInt(hex[2..3]),convertHexToInt(hex[4..5]),convertHexToInt(hex[6..7])].join(".")
}

private Integer convertHexToInt(hex) {
    Integer.parseInt(hex,16)
}

private getSmartThingsHub() {
    return location.hubs.find { it.localIP } ?: location.hubs[0]
}