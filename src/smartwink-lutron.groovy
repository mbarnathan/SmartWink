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
    log.trace "Installed with settings: ${settings}"
    initialize()
}

def updated() {
    log.trace "Updated with settings: ${settings}"
    unschedule()
    initialize()
}

def initialize() {
    log.debug "Initializing"
    unsubscribe()
    state.inDeviceDiscovery = false
}

// <editor-fold desc="Hub Discovery - returns map selectedHubs [mac: name]">

def hubDiscovery() {
    def refreshInterval = 3

    state.hubMap = state.hubMap ?: []
    state.refreshes = (state.refreshes ?: 0) + 1

    if (state.refreshes % 5 == 1) {
        startMdnsDiscovery("._wink._tcp")
    }

    dynamicPage(name:"hubDiscovery", title:"Hub Discovery Started!", nextPage:"deviceDiscovery", refreshInterval:refreshInterval, install:false, uninstall: true) {
        section("Please wait while we discover your Wink Hubs. This could take a few minutes. Select your device below once discovered.") {
            input "selectedHubs", "enum", required:true, title:"Select Wink Hub (${state.hubMap.size()} found)", multiple:true, options: state.hubMap
        }
    }
}

def startMdnsDiscovery(name) {
//	sendHubCommand(new physicalgraph.device.HubAction("lan discovery mdns/dns-sd ${name}", physicalgraph.device.Protocol.LAN))
    subscribe(location, null, onDiscovery, [filterEvents:false])
    state.hubMap = [
            "MA:AA:AA:AA:AC": "1_1_1_1:1080"
    ]
}

def onDiscovery(evt) {
    def description = evt.description
    log.trace "Wink Hub Location Event: ${description}"

    def parsedEvent = parseLanMessage(description)
    def rawMac = parsedEvent.mac
    def hub = ""
    rawMac.eachWithIndex { macChar, macIndex ->
        if (macIndex % 2 == 0 && macIndex != 0) {
            hub += ":"
        }
        hub += macChar
    }

    parsedEvent << ["hub": hub]

    if (parsedEvent.mdnsPath) {
        def hubs = getHubs()
        if (!(hubs."${parsedEvent?.mac?.toString()}")) {
            hubs << ["${parsedEvent?.mac?.toString()}": parsedEvent]
        }
        state.hubMap = hubsToMap(hubs)
    } else if (parsedEvent.headers && parsedEvent.body && parsedEvent.headers["Content-Type"]?.contains("json")) {
        log.trace "Got JSON response from Wink Hub: ${parsedEvent.body}"
        def json = new groovy.json.JsonSlurper().parseText(parsedEvent.body)
        log.trace "Parsed JSON into ${json}"
        if (state.inDeviceDiscovery) {
            populateDevices(hub, json)
        } else {
            log.warn "Not in device discovery mode, not adding device"
        }
    }
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
    def hubSerial = state.hubMap[hubMac]
    log.debug "Hub MAC: ${hubMac}, Serial: ${hubSerial}"
    def newHub = getChildDevice(hubMac) ?:
            addChildDevice("smartwink", "Wink Hub", hubMac, null, [name: "Wink Hub", completedSetup: true])
    def ip = getHubAddress(hubSerial)
    newHub.sendEvent(name:"networkAddress", value: ip)
    newHub.updateDataValue("networkAddress", ip)
    return newHub
}

// </editor-fold>

// <editor-fold desc="Device Discovery">

def deviceDiscovery() {
    log.debug "Selected hubs: ${selectedHubs}"
    def refreshInterval = 30
    state.inDeviceDiscovery = true
    state.deviceMap = state.deviceMap ?: []

    def hubsToDiscover = populateHubs(selectedHubs)
    hubsToDiscover.each { hub -> discoverLutronDevices(hub)	}

    dynamicPage(name:"deviceDiscovery", title:"Device Discovery Started!", nextPage:"deviceDiscovery", refreshInterval:refreshInterval, install:true, uninstall: false) {
        section("Lutron Devices:") {
            input "selectedDevices", "enum", required:true, title:"Select devices to manage (${state.deviceMap.size()} found)", multiple:true, options: state.deviceMap
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

	Will be called from the hub's parse() method.
*/
def populateDevices(hub, response) {
    log.debug "Got JSON data from Wink Hub (${hub}): ${response}"
    return response.collect({ asDevice(it, hub) })
}

def asDevice(jsonDevice, hub) {
    log.info "Found device ${jsonDevice}! Adding to devices."
    newDevice = getChildDevice(jsonDevice.serial) ?:
            addChildDevice("smartwink", "${jsonDevice.type}", "${jsonDevice.serial}", hub, [name: jsonDevice.name, completedSetup: true])

    return getChildDevice(jsonDevice.serial) ?:
            addChildDevice("smartwink", "${jsonDevice.type}", "${jsonDevice.serial}", hub, [name:jsonDevice.name, completedSetup: true])
}

def discoverLutronDevices(hub) {
    def netAddr = hub.getDeviceDataByName("networkAddress") ?: hub.latestState('networkAddress')?.stringValue
    def dni = hub.deviceNetworkId

    log.info("Disovering devices on ${dni}: GET http://${netAddr}/lutron.php")

    sendHubCommand(new physicalgraph.device.HubAction([
            method: "GET",
            path: "/lutron.php",
            headers: [
                    HOST: netAddr
            ]], "${dni}"))
}

// </editor-fold>
