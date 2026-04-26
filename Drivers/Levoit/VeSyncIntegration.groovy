/*

MIT License

Copyright (c) 2021-2023 Niklas Gustafsson (original upstream framework, v1.0-v1.5)
Copyright (c) 2025-2026 Dan Cox (community fork — Vital 200S support, connection pool fix, humidifier method-branch fix, v1.6+)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
*/

// History:
//
// 2026-04-25: v2.0 Community fork release (Dan Cox, level99/Hubitat-VeSync). Squashed:
//                  - Branch poll method by device type — humidifiers now use getHumidifierStatus
//                    (was using getPurifierStatus for all devices, silently failing for humidifiers)
//                  - Connection pool shutdown error retry logic (transient HTTP failures)
//                  - Vital 200S / 200S-P (LAP-V201S-*) device discovery support
//                  - Levoit Superior 6000S Humidifier (LEH-S601S-*) device discovery support
//                  - Defensive null-guard on dev lookup
// 2023-02-04: v1.5 Adding heartbeat event (Niklas Gustafsson, upstream)
// 2023-02-03: v1.4 Logging errors properly.
// 2022-08-05: v1.3 Fixed error caused by change in VeSync API for getPurifierStatus.
// 2022-07-18: v1.1 Support for Levoit Air Purifier Core 600S.
//                  Split into separate files for each device.
//                  Support for 'SwitchLevel' capability.
// 2021-10-22: v1.0 Support for Levoit Air Purifier Core 200S / 400S

import java.security.MessageDigest

metadata {
    definition(
        name: "VeSync Integration",
        namespace: "NiklasGustafsson",
        author: "Niklas Gustafsson (original); Dan Cox (fork: Vital 200S, Superior 6000S, parent fixes); elfege (contributor)",
        description: "Integrates Levoit air purifiers and humidifiers with Hubitat Elevation via VeSync cloud API",
        documentationLink: "https://github.com/level99/Hubitat-VeSync")
        {
            capability "Actuator"
            attribute "heartbeat", "string";  
        }

    preferences {
        input(name: "email", type: "string", title: "<font style='font-size:12px; color:#1a77c9'>Email Address</font>", description: "<font style='font-size:12px; font-style: italic'>VeSync Account Email Address</font>", defaultValue: "", required: true);
        input(name: "password", type: "password", title: "<font style='font-size:12px; color:#1a77c9'>Password</font>", description: "<font style='font-size:12px; font-style: italic'>VeSync Account Password</font>", defaultValue: "");
		input("refreshInterval", "number", title: "<font style='font-size:12px; color:#1a77c9'>Refresh Interval</font>", description: "<font style='font-size:12px; font-style: italic'>Poll VeSync status every N seconds</font>", required: true, defaultValue: 30)
        input("descriptionTextEnable", "bool", title: "Enable descriptive (info-level) logging?", defaultValue: true, required: false)
        input("debugOutput", "bool", title: "Enable debug logging?", defaultValue: false, required: false)
        input("verboseDebug", "bool", title: "Verbose API response logging? (Logs full response body of every VeSync API call. Useful for diagnosing 'VeSync changed an API field' issues. Sanitized automatically. Heavy on log buffer — leave off unless triaging.)", defaultValue: false, required: false)
        input("note", "hidden", title: "<b>IMPORTANT:</b> After saving driver code changes, reboot your hub for reliable HTTP operation. Connection pool errors may occur until reboot.")

        command "resyncEquipment"
        command "forceReinitialize", [[name: "Force reinitialization (use if connection errors persist after driver update)"]]

    }
}

def installed() {
	logDebug "Installed with settings: ${settings}"
    runIn(15, initialize)
}

def updated() { 
	logDebug "Updated with settings: ${settings}"

    // Set flag to stop any running tasks from old driver instance
    state.driverReloading = true
    
    // Clear any existing schedules
    unschedule()
    
    // Brief pause to let old tasks see the flag
    pauseExecution(500)

    // Clear reload flag and start initialization
    state.remove('driverReloading')
    
    // Delay initialization to allow HTTP connection pool to stabilize (15s for reliability)
	runIn(15, initialize)

    // Turn off debug log in 30 minutes
    if (settings?.debugOutput) runIn(1800, logDebugOff);
}

def uninstalled() {
    
    unschedule()

	logDebug "Uninstalled app"

	for (device in getChildDevices())
	{
		deleteChildDevice(device.deviceNetworkId)
	}	
}

def initialize() {
	logDebug "initializing"
    
    // Start the initialization chain: login → getDevices → updateDevices
    def loginSuccess = login()
    if (loginSuccess) {
        logDebug "Login successful, discovering devices..."
        getDevices()
    } else {
        logError "Login failed - check credentials and retry"
    }
}

private Boolean retryableHttp(String label, Integer maxAttempts, Closure httpCall) {
    // Handles transient "Connection pool shut down" errors
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
        try {
            return httpCall()
        }
        catch (IllegalStateException e) {
            if (e.message?.contains("Connection pool shut down")) {
                if (attempt < maxAttempts) {
                    logDebug "${label}: Connection pool error, retry ${attempt}/${maxAttempts} in 3s"
                    pauseExecution(3000)
                    continue
                }
                logError "${label}: Connection pool shut down after ${maxAttempts} attempts"
            } else {
                logError "${label}: IllegalStateException - ${e.message}"
            }
            return false
        }
        catch (Exception e) {
            logError "${label}: ${e.toString()}"
            if (e.metaClass.respondsTo(e, 'getResponse')) {
                try {
                    checkHttpResponse(label, e.getResponse())
                } catch (Exception ex) {
                    logError "${label}: Could not parse error response"
                }
            }
            return false
        }
    }
    return false
}

private Boolean login() {
    return retryableHttp("login", 3) {
        def logmd5 = MD5(password)

        def params = [
            uri: "https://smartapi.vesync.com/cloud/v1/user/login",
            contentType: "application/json",
            requestContentType: "application/json",
            body: [
                "timeZone": "America/Los_Angeles",
                "acceptLanguage": "en",
                "appVersion": "2.5.1",
                "phoneBrand": "SM N9005",
                "phoneOS": "Android",
                "traceId": "1634265366",
                "email": email,
                "password": logmd5,
                "devToken": "",
                "userType": "1",
                "method": "login"
            ],
            headers: [ 
                "Accept": "application/json",
                "Accept-Encoding": "gzip, deflate, br",
                "Connection": "keep-alive",
                "User-Agent": "Hubitat Elevation", 
                "accept-language": "en",
                "appVersion": "2.5.1",
                "tz": "America/Los_Angeles"
            ]
        ]

        logDebug "login: ${params.uri}"

        def result = false
        httpPost(params) { resp ->
            if (checkHttpResponse("login", resp)) {
                state.token = resp.data.result.token
                state.accountID = resp.data.result.accountID
                logInfo "Logged in to VeSync"
                result = true
            }
        }
        return result
    }
}

def Boolean updateDevices()
{
    // One-time pref seed: heal descriptionTextEnable=true default for users migrated from older Type without Save (forward-compat)
    if (!state.prefsSeeded) {
        if (settings?.descriptionTextEnable == null) {
            device.updateSetting("descriptionTextEnable", [type:"bool", value:true])
        }
        state.prefsSeeded = true
    }
    // Stop if driver is reloading
    if (state.driverReloading) {
        logDebug "Skipping updateDevices - driver reloading"
        return false
    }

    // Immediately schedule the next update -- this will keep the
    // referesh interval as close to constant as possible.
    runIn((int)settings.refreshInterval, updateDevices)

    sendEvent(name: "heartbeat", value: "syncing", isStateChange: true, descriptionText: "Waiting on update from VeSync servers.")
    logInfo "Heartbeat: syncing"

    for (e in state.deviceList) {

        try {
            def dni = e.key
            def configModule = e.value

            if (dni.endsWith("-nl")) continue;

            logDebug "Updating ${dni}"

            def dev = getChildDevice(dni)
            if (dev == null) {
                logDebug "Skipping ${dni} — no child device"
                continue
            }

            // Branch the API method by device type. The VeSync API uses different methods
            // for different device families: getPurifierStatus for air purifiers, getHumidifierStatus
            // for humidifiers. Calling the wrong one returns code:-1 with empty result.
            String typeName = dev.typeName ?: dev.name ?: ""
            String method = typeName.contains("Humidifier") ? "getHumidifierStatus" : "getPurifierStatus"
            def command = [
                "method": method,
                "source": "APP",
                "data": [:]
            ]

            sendBypassRequest(dev, command) { resp ->
                if (checkHttpResponse("update", resp))
                {
                    def status = resp.data.result
                    if (status == null)
                        logError "No status returned from ${method}: ${resp.msg}"
                    else
                        result = dev.update(status, getChildDevice(dni+"-nl"))
                }
            }
        }
        catch (exc)
        {
            logError exc.toString();
        }
    }

    sendEvent(name: "heartbeat", value: "synced", isStateChange: true, descriptionText: "Update received from VeSync servers.")
    logInfo "Heartbeat: synced"

    // Schedule a call to the timeout method. This will cancel any outstanding
    // schedules.
    runIn(5 * (int)settings.refreshInterval, timeOutLevoit)
}

private deviceType(code) {
    switch(code)
    {
        case "Core200S": 
        case "LAP-C201S-AUSR":
        case "LAP-C201S-WUSR":
            return "200S";
        case "Core300S": 
        case "LAP-C301S-WJP":
            return "300S";
        case "Core400S": 
        case "LAP-C401S-WJP":
        case "LAP-C401S-WUSR":
        case "LAP-C401S-WAAA":
            return "400S";
        case "Core600S": 
        case "LAP-C601S-WUS":
        case "LAP-C601S-WUSR":
        case "LAP-C601S-WEU":
            return "600S";
        case "Vital200S":
        case "LAP-V201S-WUS":
        case "LAP-V201S-WUSR":
        case "LAP-V201S-WEU":
        case "LAP-V201S-WEUR":
        case "LAP-V201S-AASR":
        case "LAP-V201S-AUSR":
            return "V200S";
        // Superior 6000S — pyvesync VeSyncSuperior6000S supports LEH-S601S-WUS/-WUSR/-WEUR and LEH-S602S-WUS
        case ~/LEH-S60[12]S-(WUS|WUSR|WEUR)/:
            return "V601S";
    }

    return "N/A";
}

private Boolean getDevices() {
    return retryableHttp("getDevices", 3) {
        def params = [
            uri: "https://smartapi.vesync.com/cloud/v1/deviceManaged/devices",
            contentType: "application/json",
            requestContentType: "application/json",
            body: [
                "timeZone": "America/Los_Angeles",
                "acceptLanguage": "en",
                "appVersion": "2.5.1",
                "phoneBrand": "SM N9005",
                "phoneOS": "Android",
                "traceId": "1634265366",
                "accountID": state.accountID,
                "token": state.token,
                "method": "devices",
                "pageNo": "1",
                "pageSize": "100"
            ],
            headers: [ 
                "tz": "America/Los_Angeles",
                "Accept": "application/json",
                "Accept-Encoding": "gzip, deflate, br",
                "Connection": "keep-alive",
                "User-Agent": "Hubitat Elevation", 
                "accept-language": "en",
                "appVersion": "2.5.1",
                "accountID": state.accountID,
                "tk": state.token 
            ]
        ]

        def result = false
        httpPost(params) { resp ->
            if (checkHttpResponse("getDevices", resp)) {
                // Defensive null checks for API response structure
                if (!resp.data) {
                    logError "getDevices: No data in response"
                    return false
                }
                if (!resp.data.result) {
                    logError "getDevices: No result in response data"
                    return false
                }
                if (!resp.data.result.list) {
                    logError "getDevices: No list in response result"
                    return false
                }
                
                def newList = [:]

                for (device in resp.data.result.list) {
                    logDebug "Device found: ${device.deviceType} / ${device.deviceName} / ${device.macID}"

                    def dtype = deviceType(device.deviceType);

                    if (dtype == "200S") {
                        newList[device.cid] = device.configModule;
                        newList[device.cid+"-nl"] = device.configModule;
                    }
                    else if (dtype == "400S" || dtype == "300S" || dtype == "600S" || dtype == "V200S" || dtype == "V601S") {
                        newList[device.cid] = device.configModule;
                    }
                }
                
                logInfo "Discovered ${resp.data.result.list.size()} VeSync device(s)"

                // Remove devices that are no longer present.
                List<com.hubitat.app.ChildDeviceWrapper> list = getChildDevices();
                if (list) list.each {
                    String dni = it.getDeviceNetworkId();
                    if (newList.containsKey(dni) == false) {
                        logInfo "Removed child device: ${dni}"
                        logDebug "Deleting ${dni}"
                        deleteChildDevice(dni);
                    }
                }

                for (device in resp.data.result.list) {
                    def dtype = deviceType(device.deviceType);
                    com.hubitat.app.ChildDeviceWrapper equip1 = getChildDevice(device.cid)

                    if (dtype == "200S") {
                        com.hubitat.app.ChildDeviceWrapper equip2 = getChildDevice(device.cid+"-nl")

                        if (equip2 == null) {
                            equip2 = addChildDevice("Levoit Core200S Air Purifier Light", device.cid+"-nl", [name: device.deviceName + " Light", label: device.deviceName + " Light", isComponent: false]);
                            equip2.updateDataValue("configModule", device.configModule);
                            equip2.updateDataValue("cid", device.cid);
                            equip2.updateDataValue("uuid", device.uuid);
                            logInfo "Added child device: ${device.deviceName} Light (Levoit Core200S Air Purifier Light)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} Light / " + dtype;
                            equip2.name = device.deviceName + " Light";
                            equip2.label = device.deviceName + " Light";
                        }                        

                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            equip1 = addChildDevice("Levoit Core200S Air Purifier", device.cid, [name: device.deviceName, label: device.deviceName, isComponent: false]);
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            logInfo "Added child device: ${device.deviceName} (Levoit Core200S Air Purifier)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                        }
                    }
                    else if (dtype == "300S") {
                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            equip1 = addChildDevice("Levoit Core300S Air Purifier", device.cid, [name: device.deviceName, label: device.deviceName, isComponent: false]);
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            logInfo "Added child device: ${device.deviceName} (Levoit Core300S Air Purifier)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                        }
                    }
                    else if (dtype == "400S") {
                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            equip1 = addChildDevice("Levoit Core400S Air Purifier", device.cid, [name: device.deviceName, label: device.deviceName, isComponent: false]);
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            logInfo "Added child device: ${device.deviceName} (Levoit Core400S Air Purifier)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                        }
                    }
                    else if (dtype == "600S") {
                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            equip1 = addChildDevice("Levoit Core600S Air Purifier", device.cid, [name: device.deviceName, label: device.deviceName, isComponent: false]);
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            logInfo "Added child device: ${device.deviceName} (Levoit Core600S Air Purifier)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                        }
                    }
                    else if (dtype == "V200S") {
                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            equip1 = addChildDevice("Levoit Vital 200S Air Purifier", device.cid, [name: device.deviceName, label: device.deviceName, isComponent: false]);
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            logInfo "Added child device: ${device.deviceName} (Levoit Vital 200S Air Purifier)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                        }
                    }
                    else if (dtype == "V601S") {
                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            equip1 = addChildDevice("Levoit Superior 6000S Humidifier", device.cid, [name: device.deviceName, label: device.deviceName, isComponent: false]);
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            logInfo "Added child device: ${device.deviceName} (Levoit Superior 6000S Humidifier)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                        }
                    }
                }                

                state.deviceList = newList
                runIn(5 * (int)settings.refreshInterval, timeOutLevoit)
                
                // Delay before first device update to ensure connection pool is stable
                runIn(10, updateDevices)
                result = true
            }
        }
        return result
    }
}

def Boolean sendBypassRequest(equipment, payload, Closure closure) {
    // Stop if driver is reloading
    if (state.driverReloading) {
        logDebug "Skipping sendBypassRequest - driver reloading"
        return false
    }
    
    logDebug "sendBypassRequest(${payload})"

    def params = [
        uri: "https://smartapi.vesync.com/cloud/v2/deviceManaged/bypassV2",
        contentType: "application/json; charset=UTF-8",
        requestContentType: "application/json; charset=UTF-8",
        body: [
            "timeZone": "America/Los_Angeles",
            "acceptLanguage": "en",
            "appVersion": "2.5.1",
            "phoneBrand": "SM N9005",
            "phoneOS": "Android",
            "traceId": "1634265366",
            "cid": equipment.getDataValue("cid"),
            "configModule": equipment.getDataValue("configModule"),
            "payload": payload,
            "accountID": getAccountID(),
            "token": getAccountToken(),
            "method": "bypassV2",
            "debugMode": false,
            "deviceRegion": "US"
        ],
        headers: [
            "tz": "America/Los_Angeles",
            "Accept": "application/json",
            "Accept-Encoding": "gzip, deflate, br",
            "Connection": "keep-alive",
            "User-Agent": "Hubitat Elevation",
            "accept-language": "en",
            "appVersion": "2.5.1",
            "accountID": getAccountID(),
            "tk": getAccountToken() 
        ]
    ]
    
    // Wrap the caller's closure with centralized API-trace logging.
    // - debugOutput on  → 1-line summary per API call (method/cid/status/inner)
    // - verboseDebug on → full response body dump (useful when VeSync changes a field name)
    // Both are sanitized via logDebug → sanitize() chain. Children's parse-time logs
    // continue to work and provide complementary parser-side observability.
    Closure tracingClosure = closure
    if (settings?.debugOutput) {
        String method   = payload?.method?.toString() ?: "?"
        String cidShort = (equipment?.getDataValue("cid") ?: "?").take(8)
        tracingClosure = { resp ->
            try {
                Integer status = resp?.status as Integer ?: -1
                def innerCode  = resp?.data?.result?.code
                logDebug "API trace: method=${method} cid=${cidShort}.. HTTP ${status} inner=${innerCode}"
                if (settings?.verboseDebug) {
                    logDebug "API response body: ${resp?.data}"
                }
            } catch (ignored) { /* never let trace logging break the API call */ }
            closure(resp)
        }
    }

    try {
        httpPost(params, tracingClosure)
        return true
    }
    catch (IllegalStateException e) {
        if (e.message?.contains("Connection pool shut down")) {
            logError "sendBypassRequest: Connection pool shut down - try again in a few seconds"
        } else {
            logError "sendBypassRequest: IllegalStateException - ${e.message}"
        }
        return false
    }
    catch (Exception e) {
        logError "sendBypassRequest: ${e.toString()}"
        return false
    }
}

// Commands -------------------------------------------------------------------------------------------------------------------

def resyncEquipment() {
  //
  // This will trigger a sensor remapping and cleanup
  //
  try {
    logDebug "resyncEquipment()"
    logInfo "Resyncing VeSync equipment"
    
    // Wait for connection pool to be ready
    pauseExecution(2000)
    getDevices()
  }
  catch (Exception e) {
    logError("Exception in resyncEquipment(): ${e}");
  }
}

def forceReinitialize() {
  //
  // Force reinitialization - use if connection errors persist
  //
  try {
    logDebug "forceReinitialize()"
    logInfo "Force reinitializing VeSync integration"
    
    // Clear state and reschedule
    state.clear()
    unschedule()
    
    // Wait longer for connection pool
    pauseExecution(5000)
    
    initialize()
  }
  catch (Exception e) {
    logError("Exception in forceReinitialize(): ${e}");
  }
}

// Helpers -------------------------------------------------------------------------------------------------------------------

def getAccountToken() {
    return state.token
}

def getAccountID() {
    return state.accountID
}

def MD5(s) {
	def digest = MessageDigest.getInstance("MD5")
	new BigInteger(1,digest.digest(s.getBytes())).toString(16).padLeft(32,"0")
} 

def parseJSON(data) {
    def json = data.getText()
    def slurper = new groovy.json.JsonSlurper()
    return slurper.parseText(json)
}

def logInfo(msg) {
    if (settings?.descriptionTextEnable) log.info sanitize(msg)
}

def logDebug(msg) {
    if (settings?.debugOutput) {
        log.debug sanitize(msg)
    }
}

def logError(msg) {
    log.error sanitize(msg)
}

// Auto-sanitize log messages so debug captures shared by users in community threads / GitHub
// issues don't leak the VeSync account email, accountID, auth token, or hashed password.
// Strips the values from any log message (regardless of which call site emitted it).
private String sanitize(msg) {
    if (msg == null) return msg
    String s = msg.toString()

    // Email — redact any address-shaped substring
    s = s.replaceAll(/[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}/, '[email-redacted]')

    // Known sensitive values from current state (exact-match replace, fast and safe)
    if (state?.accountID) s = s.replace(state.accountID as String, '[accountID-redacted]')
    if (state?.token)     s = s.replace(state.token as String,     '[token-redacted]')

    // Password should never be in logs but defensively redact if a settings dump leaks it
    if (settings?.password) s = s.replace(settings.password as String, '[password-redacted]')

    return s
}

void logDebugOff() {
  //
  // runIn() callback to disable "Debug" logging after 30 minutes
  // Cannot be private
  //
  if (settings?.debugOutput) device.updateSetting("debugOutput", [type: "bool", value: false]);
}


def checkHttpResponse(action, resp) {
	if (resp.status == 200 || resp.status == 201 || resp.status == 204)
		return true
	else if (resp.status == 400 || resp.status == 401 || resp.status == 404 || resp.status == 409 || resp.status == 500)
	{
		// Route through logError to auto-sanitize — response data on a failed login can include accountID/token
		logError "${action}: ${resp.status} - ${resp.getData()}"
		return false
	}
	else
	{
		logError "${action}: unexpected HTTP response: ${resp.status}"
		return false
	}
}

def timeOutLevoit() {
    //If the timeout expires before being reset, mark this Parent Device as 'not present' to allow action to be taken
    logInfo "Heartbeat timeout — no update from VeSync servers"
    sendEvent(name: "heartbeat", value: "not synced", isStateChange: true, descriptionText: "No update received from VeSync servers in a long time.")
}