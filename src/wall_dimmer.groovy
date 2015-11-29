/**
 *  Lutron In-Wall Dimmer device type for SmartWink. Identical to the plug-in dimmer except for the name.
 *
 *  Author: Michael Barnathan (michael@barnathan.name)
 */

metadata {
    definition(
            name: "WALL_DIMMER",
            namespace: "smartwink",
            author: "Michael Barnathan",
            description: "Lutron Caseta Wireless In-Wall Dimmer",
            category: "SmartThings Labs",
            iconUrl: "http://cdn.device-icons.smartthings.com/Home/home30-icn.png",
            iconX2Url: "http://cdn.device-icons.smartthings.com/Home/home30-icn@2x.png") {
        attribute "hubAddress", "string"

        capability "Actuator"       // no methods
        capability "Refresh"        // refresh()
        capability "Sensor"         // no methods
        capability 'Switch'   		// on(), off()
        capability 'Switch Level'   // setLevel()

        command "subscribe"
        command "unsubscribe"
    }

    simulator {
        // TODO: define status and reply messages here
    }

    tiles(scale: 2) {
        multiAttributeTile(name:"switch", type: "lighting", width: 6, height: 4, canChangeIcon: true) {
            tileAttribute ("device.switch", key: "PRIMARY_CONTROL") {
                attributeState "on", label:'on', action:"switch.off", icon:"st.Home.home30", backgroundColor:"#79b821", nextState:"turningOff"
                attributeState "off", label:'off', action:"switch.on", icon:"st.Home.home30", backgroundColor:"#ffffff", nextState:"turningOn"
                attributeState "turningOn", label:'turning on', action:"switch.off", icon:"st.switches.switch.on", backgroundColor:"#79b821", nextState:"turningOff"
                attributeState "turningOff", label:'turning off', action:"switch.on", icon:"st.switches.switch.off", backgroundColor:"#ffffff", nextState:"turningOn"
            }
            tileAttribute ("device.level", key: "SLIDER_CONTROL") {
                attributeState "level", action: "switch level.setLevel"
            }
        }

        standardTile("refresh", "device.refresh", decoration: "flat", width: 2, height: 2) {
            state "default", label:"", action:"refresh.refresh", icon:"st.secondary.refresh"
        }

        valueTile("level", "device.level", decoration: "flat", width: 2, height: 2) {
            state "level", label:'${currentValue} %', unit:"%"
        }

        valueTile("hubAddress", "device.hubAddress", decoration: "flat", width: 2, height: 2) {
            state "default", label:'Wink Hub: ${currentValue}', width: 2, height: 2
        }

        main "switch"
        details(["switch", "refresh", "level", "hubAddress"])
    }
}

// Fires when the refresh button is pressed.
def refresh() {
    log.info "Received refresh request for ${device.deviceNetworkId}"
    unsubscribe()
    subscribe()
}

def on() {
    setLevel(100)
}

def off() {
    setLevel(0)
}

def setLevel(level) {
    def oldLevel = device.currentValue("level") ?: 0

    log.info("Dimmer ${device.deviceNetworkId} level set to: ${level} from ${oldLevel}")
    if (level == oldLevel || level > 100 || level < 0) return null

    if (oldLevel != 0 && level == 0) {
        sendEvent(name: "switch", value: "turningOff")
    } else if (oldLevel == 0 && level != 0) {
        sendEvent(name: "switch", value: "turningOn")
    }

    def netAddr = device.currentValue("hubAddress")
    def dni = device.deviceNetworkId

    def scaledLevel = (level * 255 / 100) as Integer
    log.debug "Sending scaled level ${scaledLevel} / 255 to device."

    return new physicalgraph.device.HubAction([
            method: "POST",
            path: "/set.php",
            headers: [
                    HOST: netAddr,
                    "Content-Length": 0
            ],
            query: [device: dni, attr: "Level", value: scaledLevel]
    ], "${dni}")
}

def handleEvent(parsedEvent, hub, json) {
    log.info "In-wall Dimmer ${device.deviceNetworkId} handling event: ${json}"
    if (json.containsKey("value")) {
        def level = json.value as Integer
        def scaledLevel = (level * 100 / 255) as Integer
        if (scaledLevel == device.currentValue("level")) {
            // When updated from the app, the response and an independent update both come back.
            // I like keeping this behavior for redundancy, but we don't need to see updates with identical level.
            return null
        }
        log.info "Received level ${level} / 255. Setting device level to ${scaledLevel} / 100."
        sendEvent(name: "level", value: scaledLevel)
        sendEvent(name: "switch", value: (scaledLevel == 0 ? "off" : "on"))
    } else {
        log.warn "In-wall dimmer level event ${json} has no value attribute."
    }
    return null
}

def parse(description) {
    log.debug "Parsing '${description}'"

    def keyVal = description.split(":", 2)
    if (keyVal[0] in ["level", "switch"]) {
        return createEvent(name: keyVal[0], value: keyVal[1])
    } else if (keyVal[0] == "updated") {
        log.trace "In-wall dimmer was updated"
        return null
    } else {
        log.warn "Unknown event in in-wall dimmer parse(): ${description}"
        return null
    }
}

// TODO: Put in m4 block. SmartThings doesn't allow much code reuse.

private getCallBackAddress() {
    device.hub.getDataValue("localIP") + ":" + device.hub.getDataValue("localSrvPortTCP")
}

private getUpnpHost() {
    def upnp_port = 1081
    def hubAddr = device.currentValue("hubAddress")

    def addressParts = hubAddr.split(":")
    def host = addressParts[0]
    return "${host}:${upnp_port}"
}

private getUpnpPath() {
    return "/upnp/event/${device.deviceNetworkId}/level"
}

def subscribe() {
    subscribe(getUpnpHost(), getUpnpPath())
}

def unsubscribe() {
    log.info "Received unsubscribe request for ${device.deviceNetworkId}"
    unsubscribe(getUpnpHost(), getUpnpPath())
}

def subscribe(host, path) {
    def address = getCallBackAddress()
    def callbackPath = "http://${address}/notify$path"
    log.info "Received subscribe for ${device.deviceNetworkId} ($host, $path, $callbackPath)"

    new physicalgraph.device.HubAction(
            method: "SUBSCRIBE",
            path: path,
            headers: [
                    HOST: host,
                    CALLBACK: "<${callbackPath}>",
                    NT: "upnp:event",
                    TIMEOUT: "Second-3600"
            ]
    )
}

def unsubscribe(host, path) {
    def sid = getDeviceDataByName("subscriptionId")
    if (!sid) {
        log.info "Unsubscribe without sid, probably not subscribed yet."
        return null
    }
    log.trace "unsubscribe($host, $path, $sid)"
    new physicalgraph.device.HubAction(
            method: "UNSUBSCRIBE",
            path: path,
            headers: [
                    HOST: host,
                    SID: "uuid:${sid}",
            ]
    )
}
