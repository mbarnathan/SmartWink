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
}

def installed() {
    log.trace "SmartWink Installed with settings: ${settings}"
    state.inDeviceDiscovery = false
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
        startMdnsDiscovery("._wink._tcp")
    }

    dynamicPage(name:"hubDiscovery", title:"Hub Discovery Started!", nextPage:"deviceDiscovery", refreshInterval:refreshInterval, install:false, uninstall: true) {
        section("Please wait while we discover your Wink Hubs. This could take a few minutes. Select your device below once discovered.") {
            input "selectedHubs", "enum", required:true, title:"Select Wink Hub (${state.hubMacToIp.size()} found)", multiple:true, options: state.hubMacToIp
        }
    }
}

def startMdnsDiscovery(name) {
//	sendHubCommand(new physicalgraph.device.HubAction("lan discovery mdns/dns-sd ${name}", physicalgraph.device.Protocol.LAN))
    state.hubMacToIp = [
            "34:23:BA:EC:17:52": "10_3_0_5:1080"
    ]
    populateHubs(state.hubMacToIp.keySet())
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

    if (parsedEvent.mdnsPath) {
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
    def hubs = getHubs()
    if (!(hubs."${parsedEvent?.mac?.toString()}")) {
        hubs << ["${parsedEvent?.mac?.toString()}": parsedEvent]
    }
    state.hubMacToIp = hubsToMap(hubs)
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

def getHubs() {
    if (!state.hubs) { state.hubs = [:] }
    state.hubs
}

private Map hubsToMap(hubs) {
    def map = [:]
    hubs.each {
        def value = "${it.value.name}"
        def key = "${it.value.mac}"
        map["${key}"] = value
    }
    return map
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
    state.discoveredDevices = state.discoveredDevices ?: [:]

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
    state.deviceRefreshes = (state.deviceRefreshes ?: 0) + 1

    if (state.deviceRefreshes % 5 == 1) {
        selectedHubs.each { discoverLutronDevices(getChildDevice(it)) }
    }

    dynamicPage(name:"deviceDiscovery", title:"Device Discovery Started!", nextPage:"deviceDiscovery", refreshInterval:refreshInterval, install:true, uninstall: false) {
        section("Lutron Devices:") {
            input "selectedDevices", "enum", required:true, title:"Select devices to manage (${deviceMap.size()} found)", multiple:true, options: deviceMap
        }
    }
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
    log.info "Found device ${jsonDevice}! Adding to devices."
    def device = getChildDevice("${jsonDevice.serial}")
    if (!device) {
        def winkHub = getChildDevice(hub)
        def hubAddr = winkHub.getDataValue("networkAddress")
        device = addChildDevice("smartwink", "${jsonDevice.type}", "${jsonDevice.serial}", location.hubs[0].id,
                                [name: jsonDevice.name, completedSetup: true])
        device.sendEvent(name:"hubAddress", value: hubAddr)
        device.updateDataValue("hubAddress", hubAddr)
        device.refresh()
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
