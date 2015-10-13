/**
 *  Wink Hub device type for SmartWink. Your Wink hub needs to be rooted to use this.
 *
 *  Author: Michael Barnathan (michael@barnathan.name)
 */

metadata {
    definition(
            name: "Wink Hub",
            namespace: "smartwink",
            author: "Michael Barnathan",
            description: "A rooted Wink Hub configured to use SmartWink",
            category: "SmartThings Labs",
            iconUrl: "https://s3.amazonaws.com/smartapp-icons/Partner/hue.png",
            iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Partner/hue@2x.png") {
        attribute "networkAddress", "string"
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
        valueTile("networkAddress", "device.networkAddress", decoration: "flat", height: 2, width: 4, inactiveLabel: false) {
            state "default", label:'${currentValue}', height: 1, width: 2, inactiveLabel: false
        }

        main (["icon"])
        details(["rich-control", "networkAddress"])
    }
}

def parse(description) {
    log.debug "Parsing '${description}'"
    if (description == "updated") {
        //do nothing
        log.trace "Wink hub was updated"
        return [];
    }

    def results = [];
    def map = (description instanceof String) ? stringToMap(description) : description
    if (map?.name && map?.value) {
        log.trace "Wink hub, GENERATING EVENT: $map.name: $map.value"
        results << createEvent(name: "${map.name}", value: "${map.value}")
    } else {
        log.trace "Parsing description"
        def msg = parseLanMessage(description)
        if (msg.body) {
            def contentType = msg.headers["Content-Type"]
            if (contentType?.contains("json")) {
                def json = new groovy.json.JsonSlurper().parseText(msg.body)
                if (parent.state.inDeviceDiscovery) {
                    log.info parent.populateDevices(this, json)
                }
            }
        }
    }
    results
}
