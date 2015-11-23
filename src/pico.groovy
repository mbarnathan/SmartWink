/**
 *  Lutron Pico Remote device type for SmartWink.
 *
 *  Author: Michael Barnathan (michael@barnathan.name)
 */

metadata {
    definition(
            name: "PICO",
            namespace: "smartwink",
            author: "Michael Barnathan",
            description: "Lutron Pico Remote",
            category: "SmartThings Labs",
            iconUrl: "https://s3.amazonaws.com/smartapp-icons/Partner/hue.png",
            iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Partner/hue@2x.png") {
        attribute "hubAddress", "string"

        capability "Actuator"       // no methods
        capability "Button"         // supplies button.held and button.pushed
        capability "Refresh"        // supplies refresh()
        capability "Sensor"         // no methods

        command "subscribe"
        command "unsubscribe"
    }

    simulator {
        // TODO: define status and reply messages here
    }

    tiles(scale: 2) {
        multiAttributeTile(name:"rich-control"){
            tileAttribute ("device.switch", key: "PRIMARY_CONTROL") {
                attributeState "default", label: "Wink Hub", action: "", icon: "st.Lighting.light99-hue", backgroundColor: "#F3C200"
            }
        }
        standardTile("icon", "icon", width: 1, height: 1, canChangeIcon: false, inactiveLabel: true, canChangeBackground: false) {
            state "default", label: "Wink Hub", action: "", icon: "st.Lighting.light99-hue", backgroundColor: "#FFFFFF"
        }
        valueTile("hubAddress", "device.hubAddress", decoration: "flat", height: 2, width: 4, inactiveLabel: false) {
            state "default", label:'${currentValue}', height: 1, width: 2, inactiveLabel: false
        }

        main (["icon"])
        details(["rich-control", "hubAddress"])
    }
}

// Fires when the refresh button is pressed.
def refresh() {
    log.info "Received refresh request for ${device.deviceNetworkId}"
    unsubscribe()
    subscribe()
}

def subscribe() {
    subscribe(getUpnpHost(), getUpnpPath())
}

def unsubscribe() {
    log.info "Received unsubscribe request for ${device.deviceNetworkId}"
    unsubscribe(getUpnpHost(), getUpnpPath())
}

private getUpnpHost() {
    def upnp_port = 1081
    log.debug(dump())
    def hubAddr = device.currentValue("hubAddress")

    def addressParts = hubAddr.split(":")
    def host = addressParts[0]
    return "${host}:${upnp_port}"
}

private getUpnpPath() {
    return "/upnp/event/${device.deviceNetworkId}/button"
}

def subscribe(host, path) {
    def address = getCallBackAddress()
    def callbackPath = "http://${address}/notify$path"
    log.trace "subscribe($host, $path, $callbackPath)"

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
                    HOST: formatHost(host),
                    SID: "uuid:${sid}",
            ]
    )
}

private getCallBackAddress() {
    device.hub.getDataValue("localIP") + ":" + device.hub.getDataValue("localSrvPortTCP")
}

def handleEvent(parsedEvent, hub, json) {
    log.info "Pico remote ${device.deviceNetworkId} handling event: ${json}"
    if (json.containsKey("button")) {
        def button = json.button as Integer
        log.info "Sending button pushed event for button ${button}"
        sendEvent(name: "button", value: "pushed", data: [ buttonNumber: button ], descriptionText: "Button ${button} pressed.", isStateChange: true)
    } else {
        log.warn "Pico remote press event ${json} has no button attribute."
    }
    return null
}

def parse(description) {
    log.debug "Parsing '${description}'"
    if (description == "updated") {
        log.trace "Pico remote was updated"
        return [];
    }

    def results = [];
    def map = (description instanceof String) ? stringToMap(description) : description
    if (map?.name && map?.value) {
        log.trace "Pico Remote, GENERATING EVENT: $map.name: $map.value"
        results << createEvent(name: "${map.name}", value: "${map.value}")
    } else {
        def msg = parseLanMessage(description)
        log.trace "Parsing Pico Remote description to ${msg}"
        if (msg.body) {
            def contentType = msg.headers["Content-Type"]
            if (contentType?.contains("json")) {
                def json = new groovy.json.JsonSlurper().parseText(msg.body)
            }
        }
    }
    results
}
