/**dnl
include(`app_constants.m4')dnl
define(`__name__', `PICO')dnl
define(`__human_name__', `Pico remote')dnl 
define(`__description__', `Lutron Pico Remote')dnl
define(`__author__', `Michael Barnathan')dnl
define(`__author_email__', `michael@barnathan.name')dnl
define(`__icon__', `http://cdn.device-icons.smartthings.com/Home/home30-icn.png')dnl
define(`__icon2x__', `http://cdn.device-icons.smartthings.com/Home/home30-icn@2x.png')dnl
define(`__on_icon__', `st.Home.home30')dnl
define(`__off_icon__', `st.Home.home30')dnl
define(`__on_color__', `#79b821')dnl
define(`__off_color__', `#ffffff')dnl
include(`header.m4')
 */

metadata {
    definition(
        // include(`definition.m4')
    ) {
        attribute "hubMac", "string"
        attribute "actAsDimmer", "enum", ["off", "on"]

        capability "Actuator"       // no commands
        capability "Button"         // button.held and button.pushed
        capability "Refresh"        // refresh()
        capability "Sensor"         // no commands
        capability "Switch"         // on(), off()
        capability 'Switch Level'   // setLevel(). Implemented in software to allow dimmer events.
                                    // (Picos have no hardware level indicators at this time).
        command "buttonUp"
        command "buttonDown"
        command "buttonOn"
        command "buttonOff"
        command "buttonFavorite"

        command "enableDimming"
        command "disableDimming"
        command "subscribe"
        command "unsubscribe"
    }

    simulator {}

    tiles(scale: 2) {
        multiAttributeTile(name:"switch", type: "lighting", width: 6, height: 4, canChangeIcon: true) {
            tileAttribute ("device.switch", key: "PRIMARY_CONTROL") {
                attributeState "on", label:'on', action:"switch.off", icon:"__on_icon__", backgroundColor:"__on_color__", nextState:"off"
                attributeState "off", label:'off', action:"switch.on", icon:"__off_icon__", backgroundColor:"__off_color__", nextState:"on"
            }
            tileAttribute ("device.level", key: "SLIDER_CONTROL") {
                attributeState "level", action: "switch level.setLevel"
            }
        }

        standardTile("refresh", "device.refresh", decoration: "flat", width: 2, height: 2) {
            state "default", label:"", action:"refresh.refresh", icon:"st.secondary.refresh"
        }

        standardTile("dimmerButtons", "device.actAsDimmer", width: 2, height: 2, decoration: "flat") {
            state "off", label: "Buttons Don't Dim", action: "enableDimming", icon: "st.Home.home30"
            state "on", label: 'Buttons Dim', action: "disableDimming", icon: "st.Lighting.light14", defaultState: true
        }

        valueTile("level", "device.level", decoration: "flat", width: 2, height: 2) {
            state "level", label:'${currentValue} %', unit:"%"
        }

        standardTile("buttonOn", "device.button", decoration: "flat", width: 2, height: 1) {
            state "", label:'ON', action: "buttonOn", icon: "st.Lighting.light11"
        }

        standardTile("buttonOff", "device.button", decoration: "flat", width: 2, height: 1) {
            state "", label:'OFF', action: "buttonOff", icon: "st.Lighting.light13"
        }

        standardTile("buttonUp", "device.button", decoration: "flat", width: 2, height: 1) {
            state "", label:'UP', action: "buttonUp", icon: "st.Weather.weather11"
        }

        standardTile("buttonDown", "device.button", decoration: "flat", width: 2, height: 1) {
            state "", label:'DOWN', action: "buttonDown", icon: "st.Weather.weather13"
        }

        standardTile("buttonFavorite", "device.button", decoration: "flat", width: 2, height: 1) {
            state "", label:'FAVORITE', action: "buttonFavorite", icon: "st.Seasonal Winter.seasonal-winter-014"
        }

        valueTile("hubMac", "device.hubMac", decoration: "flat", width: 2, height: 1) {
            state "default", label:'Wink Hub: ${currentValue}', width: 2, height: 1
        }

        main "switch"
        details(["switch", "refresh", "dimmerButtons", "level", "buttonOn", "buttonOff", "buttonFavorite", "buttonUp", "buttonDown", "hubMac"])
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
    def oldLevel = device.currentValue("level")

    log.info("__human_name__ ${device.deviceNetworkId} set level: ${level} from ${oldLevel}")
    if (level == oldLevel || level > 100 || level < 0) return null

    sendEvent(name: "level", value: level)

    if (level == 0) {
        sendEvent(name: "switch", value: "off")
    } else if (oldLevel == 0) {
        sendEvent(name: "switch", value: "on")
    }
}

def incrementLevel(int increment) {
    setLevel((device.currentValue("level") ?: 0) + increment)
}

private boolean isActingAsDimmer() {
    return device.currentValue("actAsDimmer") == "on"
}

def enableDimming() {
    sendEvent(name: "actAsDimmer", value: "on")
}

def disableDimming() {
    sendEvent(name: "actAsDimmer", value: "off")
}

private int BUTTON_ON() { return 2 }
private int BUTTON_OFF() { return 4 }
private int BUTTON_UP() { return 5 }
private int BUTTON_DOWN() { return 6 }
private int BUTTON_FAVORITE() { return 3 }

private buttonPressed(int button) {
    sendEvent(name: "button", value: "pushed", data: [ buttonNumber: button ],
              descriptionText: "Button ${button} pressed.", isStateChange: true)

    // If acting as a dimmer, link the buttons to the level.
    if (isActingAsDimmer()) {
        switch (button) {
            case BUTTON_ON():
                on()
                break
            case BUTTON_OFF():
                off()
                break
            case BUTTON_UP():
                incrementLevel(5)
                break
            case BUTTON_DOWN():
                incrementLevel(-5)
                break
            case BUTTON_FAVORITE():
                // TODO: Implement favorite button
                break
            default:
                break
        }
    }
}

def buttonDown() { return buttonPressed(BUTTON_DOWN()) }
def buttonUp() { return buttonPressed(BUTTON_UP()) }
def buttonOn() { return buttonPressed(BUTTON_ON()) }
def buttonOff() { return buttonPressed(BUTTON_OFF()) }
def buttonFavorite() { return buttonPressed(BUTTON_FAVORITE()) }

def handleEvent(parsedEvent, hub, json) {
    log.info "__human_name__ ${device.deviceNetworkId} handling event: ${json}"
    if (json.containsKey("button")) {
        def button = json.button as Integer
        log.info "Sending button pushed event for button ${button}"
        buttonPressed(button)
    } else {
        log.warn "__human_name__ press event ${json} has no button attribute."
    }
    return null
}

def parse(description) {
    log.debug "Parsing '${description}'"
    if (description == "updated") {
        log.trace "__human_name__ was updated"
        return [];
    }

    // Only called from the device tile - the SmartApp's location subscription handles the physical button presses
    // via handleEvent (since this device doesn't have an IP address and events aren't forwarded automatically to
    // parse() by SmartThings).
    def keyVal = description.split(":", 2)
    if (keyVal[0] == "pushed") {
        return createEvent(name: "button", value: keyVal[0], data: [buttonNumber: keyVal[1] as Integer],
                           descriptionText: "Button ${button} pressed in app.", isStateChange: true)
    } else if (keyVal[0] in ["level", "switch"]) {
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
