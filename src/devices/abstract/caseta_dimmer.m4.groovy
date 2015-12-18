
metadata {
    definition(
        // include(`definition.m4')
    ) {
        attribute "hubMac", "string"

        capability "Actuator"       // no commands
        capability "Refresh"        // refresh()
        capability "Sensor"         // no commands
        capability "Switch"   	    // on(), off()
        capability "Switch Level"   // setLevel()

        command "subscribe"
        command "unsubscribe"
    }

    simulator {}

    tiles(scale: 2) {
        multiAttributeTile(name:"switch", type: "lighting", width: 6, height: 4, canChangeIcon: true) {
            tileAttribute ("device.switch", key: "PRIMARY_CONTROL") {
                attributeState "on", label:'${name}', action:"switch.off", icon:"__on_icon__", backgroundColor:"__on_color__", nextState:"turningOff"
                attributeState "off", label:'${name}', action:"switch.on", icon:"__off_icon__", backgroundColor:"__off_color__", nextState:"turningOn"
                attributeState "turningOn", label:'${name}', action:"switch.off", icon:"__on_icon__", backgroundColor:"__on_color__", nextState:"turningOff"
                attributeState "turningOff", label:'${name}', action:"switch.on", icon:"__off_icon__", backgroundColor:"__off_color__", nextState:"turningOn"
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

        valueTile("hubMac", "device.hubMac", decoration: "flat", width: 2, height: 2) {
            state "default", label:'Wink Hub: ${currentValue}', width: 2, height: 2
        }

        main "switch"
        details(["switch", "refresh", "level", "hubMac"])
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

    log.info("__human_name__ ${device.deviceNetworkId} level set to: ${level} from ${oldLevel}")
    if (level == oldLevel || level > 100 || level < 0) return null

    if (oldLevel != 0 && level == 0) {
        sendEvent(name: "switch", value: "turningOff")
    } else if (oldLevel == 0 && level != 0) {
        sendEvent(name: "switch", value: "turningOn")
    }

    def netAddr = hubIp()
    def dni = device.deviceNetworkId

    int scaledLevel = level * 255 / 100
    log.debug "Sending scaled level ${scaledLevel} / 255 to device."

    return new physicalgraph.device.HubAction([
            method: "POST",
            path: "/set.php",
            headers: [
                    HOST: "${netAddr}:80",
                    "Content-Length": 0
            ],
            query: [device: dni, attr: "Level", value: "${scaledLevel}"]
    ], "${dni}")
}

def handleEvent(parsedEvent, hub, json) {
    log.info "__human_name__ ${device.deviceNetworkId} handling event: ${json}"
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
        log.warn "__human_name__ level event ${json} has no value attribute."
    }
    return null
}

def parse(description) {
    log.debug "Parsing '${description}'"

    def keyVal = description.split(":", 2)
    if (keyVal[0] in ["level", "switch"]) {
        return createEvent(name: keyVal[0], value: keyVal[1])
    } else if (keyVal[0] == "updated") {
        log.trace "__human_name__ was updated"
        return null
    } else {
        log.warn "Unknown event in __human_name__ parse(): ${description}"
        return null
    }
}

// include(`capabilities/subscribable.m4.groovy')
