/**dnl
 include(`app_constants.m4')dnl
 define(`__name__', `Hardlink Pico')dnl
 define(`__human_name__', `Hardlink Pico and Caseta')dnl
 define(`__description__', ``Pairs a set of Pico remotes to a set of Caseta dimmers in hardware, bypassing SmartThings. Only Clear-Connect based dimmers may be paired this way.'')dnl
 define(`__file_description__', __description__)dnl
 define(`__author__', `Michael Barnathan')dnl
 define(`__author_email__', `michael@barnathan.name')dnl
 define(`__icon__', `http://cdn.device-icons.smartthings.com/Weather/weather13-icn@2x.png')dnl
 define(`__icon2x__', `http://cdn.device-icons.smartthings.com/Weather/weather13-icn@2x.png')dnl
 include(`header.m4')
 */

definition(
        // include(`definition.m4')
)

preferences {
    // Page definition disables mode selection, since the hard pair is totally outside of SmartThings' mode control.
    page(name: "Device Selection", title: "Select devices to hard-pair", install: true, uninstall: true) {
        section("Instructions") {
            paragraph "Pico remotes support direct hardware pairing with other Clear Connect devices. This is the fastest and most reliable way to synchronize Pico remotes and Caseta dimmers. If non-Clear Connect dimmers are selected, they will not be paired."
            paragraph "Pairing may take up to 10 seconds per device added or removed, per Pico. Please be patient."
        }

        section("Devices to link:") {
            input "picos", "device.pico", title: "Pico Remotes:", multiple: true, required: true
            input "dimmers", "capability.switchLevel", title: "Caseta Dimmers:", multiple: true, required: true
        }

        section {
            label(name: "label", title: "Assign a name", required: false)
        }
    }
}

def installed() {}

def updated() {
    def newPicoIds = getIds(picos)
    def removedPicoIds = (state.lastPicos) ? (state.lastPicos - newPicoIds) : []
    state.lastPicos = newPicoIds
    if (removedPicoIds) {
        log.info "Pico remotes ${removedPicoIds} removed. Removing device pairings."
        pair(removedPicoIds, [])
    }

    log.info "Pairing list for ${picos} updated: ${dimmers}. Updating device pairings."
    pair(newPicoIds, getIds(dimmers))
}

def uninstalled() {
    if (picos) {
        log.info "Unpairing all dimmer ties for ${picos}"
        pair(getIds(picos), [])
    }
}

private getIds(devices) {
    return devices.collect { it.device.deviceNetworkId }
}

private pair(remoteIds, deviceIds) {
    if (!remoteIds) {
        // Uninstalled before the remote was selected.
        return
    }

    def idStr = deviceIds.join(",")
    def remoteStr = remoteIds.join(",")
    log.info "Setting remote pairing for [${remoteStr}] to [${idStr}]"

    // This is a hack, but the best way to get at the Wink Hub's address while batching in one request.
    // When not batched, the requests race for DB access and cause problems.
    picos[0].setPairList(idStr, remoteStr)
}
