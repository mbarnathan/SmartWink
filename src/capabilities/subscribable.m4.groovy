// This device can request and handle subscriptions.

private getCallBackAddress() {
    device.hub.getDataValue("localIP") + ":" + device.hub.getDataValue("localSrvPortTCP")
}

private getUpnpHost() {
    def upnp_port = 1081
    def hubAddr = hubIp()

    def addressParts = hubAddr.split(":")
    def host = addressParts[0]
    return "${host}:${upnp_port}"
}

private getUpnpPath() {
    return "/upnp/event/${device.deviceNetworkId}/all"
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
                    TIMEOUT: "Second-9999999999999"
            ]
    )
}

def unsubscribe(host, path) {
    def sid = getDeviceDataByName("subscriptionId")
    if (!sid) {
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

def hubIp() {
    return parent.state.foundHubs[device.currentValue("hubMac").replaceAll(":", "")]
}