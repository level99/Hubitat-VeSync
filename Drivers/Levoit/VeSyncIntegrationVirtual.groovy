/*

MIT License

Copyright (c) 2026 Dan Cox (community fork — level99/Hubitat-VeSync)

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
// 2026-04-29: v2.3 (community fork, level99/Hubitat-VeSync, by Dan Cox)
//                  - Phase 1: driver skeleton + Core 200S fixture hand-coded.
//                  - Fixture-driven virtual parent for child driver development
//                    without owning the hardware.
//                  - Spawns child devices bound to canned fixtures, routes
//                    sendBypassRequest calls through fixture data, validates
//                    payload data keys against pyvesync canonical shapes.
//                  - [DEV TOOL] — Do NOT install for normal use.

import groovy.transform.Field

// ---------------------------------------------------------------------------
// Fixture-to-driver metadata maps
// ---------------------------------------------------------------------------
// Phase 1: one fixture. Phase 1.5 regenerator will extend these.
// The driver name strings MUST match the real child driver's definition(name:)
// exactly — Hubitat resolves child drivers by name string on addChildDevice().

@Field static final Map FIXTURE_TO_DRIVER = [
    "Core200S": "Levoit Core200S Air Purifier"
]

@Field static final Map FIXTURE_TO_DEVICETYPE = [
    "Core200S": "Core200S"
]

// configModule from pyvesync device_map.py for Core 200S (LAP-C201S-WAAA):
// VeSyncAirBypass: WifiBTOnboardingNotificationsCore200S
@Field static final Map FIXTURE_TO_CONFIGMODULE = [
    "Core200S": "WifiBTOnboardingNotificationsCore200S"
]

// ---------------------------------------------------------------------------
// Core 200S default state (hand-curated from Core200S.yaml device_on_manual_speed1)
// Mirrors the shape that the Core200S child's update(status, nightLight) reads:
//   status.result.enabled, status.result.level, status.result.mode,
//   status.result.filter_life, status.result.night_light,
//   status.result.display, status.result.child_lock
// ---------------------------------------------------------------------------

@Field static final Map CORE_200S_DEFAULT_STATE = [
    enabled     : true,
    level       : 1,
    mode        : "manual",
    filter_life : 80,
    night_light : "on",
    display     : true,
    child_lock  : false,
    extension   : [timer_remain: 0],
    device_error_code: 0
].asImmutable()

// ---------------------------------------------------------------------------
// Fixture operation metadata: method name -> expected data keys
// Used for payload validation in sendBypassRequest.
// setSwitch/setLevel/setPurifierMode/getPurifierStatus derived from Core200S.yaml
// (pyvesync canonical fixture). setDisplay/setChildLock/addTimer/delTimer/resetFilter
// derived from LevoitCore200S.groovy source (v2.3 additions — pyvesync upstream
// fixture not yet updated with these ops; tracked in TODO.md).
// ---------------------------------------------------------------------------

@Field static final Map FIXTURE_OPS = [
    "Core200S": [
        [methodName: "setSwitch",       dataKeys: ["enabled", "id"]              as Set],
        [methodName: "setLevel",        dataKeys: ["level", "id", "type"]        as Set],
        [methodName: "setPurifierMode", dataKeys: ["mode"]                       as Set],
        [methodName: "getPurifierStatus", dataKeys: []                           as Set],
        [methodName: "setDisplay",      dataKeys: ["state"]                      as Set],
        [methodName: "setChildLock",    dataKeys: ["child_lock"]                 as Set],
        [methodName: "addTimer",        dataKeys: ["action", "total"]            as Set],
        [methodName: "delTimer",        dataKeys: ["id"]                         as Set],
        [methodName: "resetFilter",     dataKeys: []                             as Set]
    ]
]

// ---------------------------------------------------------------------------
// State mutation map: method -> closure (state, data) -> void
// updateCanonicalState() dispatches here to maintain per-DNI snapshots.
// ---------------------------------------------------------------------------

@Field static final Map STATE_MUTATORS = [
    "setSwitch": { Map snap, Map data ->
        if (data?.containsKey("enabled")) snap.enabled = data.enabled
    },
    "setLevel": { Map snap, Map data ->
        if (data?.containsKey("level")) snap.level = data.level
        snap.mode = "manual"
    },
    "setPurifierMode": { Map snap, Map data ->
        if (data?.containsKey("mode")) snap.mode = data.mode
    },
    "setDisplay": { Map snap, Map data ->
        if (data?.containsKey("state")) snap.display = data["state"]
    },
    "setChildLock": { Map snap, Map data ->
        if (data?.containsKey("child_lock")) snap.child_lock = data.child_lock
    },
    "addTimer": { Map snap, Map data ->
        // Record that timer is active; actual remaining time is not simulated
        snap.extension = snap.extension ?: [:]
        snap.extension.timer_remain = data?.total ?: 0
    },
    "delTimer": { Map snap, Map data ->
        snap.extension = snap.extension ?: [:]
        snap.extension.timer_remain = 0
    },
    "resetFilter": { Map snap, Map data ->
        snap.filter_life = 100
    }
]

metadata {
    definition(
        name: "VeSync Virtual Test Parent",
        namespace: "level99",
        author: "Dan Cox",
        description: "[DEV TOOL] Fixture-driven test harness for child driver development without owning the hardware. Do NOT install for normal use — does not connect to VeSync cloud.",
        importUrl: "https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/Drivers/Levoit/VeSyncIntegrationVirtual.groovy",
        documentationLink: "https://github.com/level99/Hubitat-VeSync/blob/main/CONTRIBUTING.md",
        singleThreaded: true,
        version: "2.3"
    ) {
        capability "Refresh"

        // Reflects current operational mode of the virtual parent.
        // "ready"                       — no real parent detected, safe to spawn
        // "blocked-real-parent-installed" — real VeSync Integration detected; spawn blocked
        // "spawning"                    — spawnFromFixture in progress
        attribute "fixtureMode",          "string"
        attribute "lastFixtureLoaded",    "string"
        attribute "lastSendBypassMethod", "string"

        command "spawnFromFixture",  [
            [name: "Fixture", type: "ENUM", constraints: ["Core200S"]],
            [name: "Child label", type: "STRING"]
        ]
        command "resetAllChildren"
        command "stepFixtureResponse", [
            [name: "Child DNI",  type: "STRING"],
            [name: "Op name",    type: "STRING"]
        ]
    }

    preferences {
        input("descriptionTextEnable", "bool",
              title: "Enable info logging",
              defaultValue: true)
        input("debugOutput", "bool",
              title: "Enable debug logging",
              defaultValue: false)
        // verboseDebug defaults TRUE — this is a dev tool; contributors want full traces.
        input("verboseDebug", "bool",
              title: "Full payload + response trace (default ON for dev tool)",
              defaultValue: true)
    }
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

def installed() {
    logInfo "[DEV TOOL] Virtual parent installed."
    runIn(1, "runPreFlight")
    // BP12 pref-seed
    if (!state.prefsSeeded) {
        if (settings?.descriptionTextEnable == null)
            device.updateSetting("descriptionTextEnable", [type:"bool", value:true])
        state.prefsSeeded = true
    }
}

def updated() {
    logDebug "updated()"
    // BP12 pref-seed
    if (!state.prefsSeeded) {
        if (settings?.descriptionTextEnable == null)
            device.updateSetting("descriptionTextEnable", [type:"bool", value:true])
        state.prefsSeeded = true
    }
    // BP16: record debug enable timestamp; happy-path runIn disables after 30 min
    if (settings?.debugOutput) {
        runIn(1800, "logDebugOff")
        state.debugEnabledAt = now()
    } else {
        state.remove("debugEnabledAt")
    }
    runIn(1, "runPreFlight")
}

def initialize() {
    logDebug "initialize()"
}

def uninstalled() {
    logDebug "uninstalled()"
    getChildDevices()?.each { child ->
        deleteChildDevice(child.deviceNetworkId)
    }
}

def refresh() {
    logDebug "refresh()"
    ensureDebugWatchdog()
    Map currentBindings = state.childFixtures ?: [:]
    Map currentSnapshots = state.fixtureSnapshots ?: [:]
    currentBindings.each { dni, fixtureName ->
        def child = getChildDevice(dni as String)
        if (child) {
            Map response = synthesizeStatusResponse(dni as String, fixtureName as String)
            child.update(response, null)
        }
    }
}

// ---------------------------------------------------------------------------
// Pre-flight: detect real parent conflict
// ---------------------------------------------------------------------------

// Scheduled by installed() and updated() — deferred so device is fully
// initialized before we try to read child devices or send events.
void runPreFlight() {
    boolean conflict = detectRealParent()
    if (conflict) {
        sendEvent(name: "fixtureMode", value: "blocked-real-parent-installed")
        logError "[DEV TOOL] Real VeSync Integration parent detected on this hub. " +
                 "The virtual parent is blocked to prevent interference with live devices. " +
                 "Uninstall VeSync Integration before using the virtual test parent, or " +
                 "uninstall this virtual parent."
    } else {
        sendEvent(name: "fixtureMode", value: "ready")
        logInfo "[DEV TOOL] Pre-flight clear — no real parent detected."
    }
}

/**
 * Returns true if a real VeSync Integration parent device (driver-type parent, not app)
 * is present on this hub.
 *
 * Detection strategy: scan child devices of all discovered devices (not reliable for
 * a parent driver since it doesn't have a fixed parent/child relationship with the virtual
 * parent). Instead, use the DNI-prefix convention: real VeSync Integration children are
 * created with DNIs matching the device's CID from the VeSync cloud, which are typically
 * alphanumeric strings without the "VirtualVeSync-" prefix we assign.
 *
 * Simpler fallback: check state.realParentDetected flag, which tests can inject.
 * This allows specs to simulate the blocked state without needing real app introspection.
 *
 * In production: we check getChildDevices() for any DNI NOT matching our own
 * "VirtualVeSync-" prefix — if any such child exists, another parent manages it.
 *
 * NOTE: Hubitat drivers cannot introspect installed apps (that's an app API).
 * We use child-device DNI patterns as the practical detection signal.
 *
 * LIMITATION: this heuristic produces a false-negative when the real VeSync Integration
 * parent is installed but has not yet discovered/spawned any child devices (e.g. right
 * after first install before Resync Equipment is clicked). In that window,
 * detectRealParent() returns false and spawnFromFixture() proceeds. If you later run
 * Resync Equipment on the real parent, you will end up with both real and virtual
 * children on the hub — a confusing state. To avoid this: ensure the real VeSync
 * Integration parent is fully uninstalled (including all its child devices) before
 * using this virtual test parent. Do not install both on the same hub simultaneously.
 */
private boolean detectRealParent() {
    // Spec-injectable override: tests set state.realParentDetected = true to simulate conflict
    if (state.realParentDetected == true) return true

    // Production heuristic: any child device present with a non-virtual DNI
    // means another parent (likely the real VeSyncIntegration) is managing it.
    def allChildren = getChildDevices() ?: []
    return allChildren.any { child ->
        String dni = child?.deviceNetworkId ?: ""
        // Our own children always have the "VirtualVeSync-" prefix
        !dni.startsWith("VirtualVeSync-")
    }
}

// ---------------------------------------------------------------------------
// spawnFromFixture
// ---------------------------------------------------------------------------

def spawnFromFixture(String fixtureName, String childLabel) {
    logDebug "spawnFromFixture(${fixtureName}, ${childLabel})"
    ensureDebugWatchdog()

    // Pre-flight conflict check (also fires at installed/updated but re-verify here)
    if (detectRealParent()) {
        sendEvent(name: "fixtureMode", value: "blocked-real-parent-installed")
        logError "[DEV TOOL] spawnFromFixture blocked — real VeSync Integration detected."
        return
    }

    if (!FIXTURE_TO_DRIVER.containsKey(fixtureName)) {
        logError "[DEV TOOL] Unknown fixture '${fixtureName}'. Known fixtures: ${FIXTURE_TO_DRIVER.keySet()}"
        return
    }

    String labelSafe = (childLabel ?: fixtureName).replaceAll(/\s+/, '-')
    String virtualDni = "VirtualVeSync-${fixtureName}-${labelSafe}"
    String driverName = FIXTURE_TO_DRIVER[fixtureName]
    String deviceType = FIXTURE_TO_DEVICETYPE[fixtureName]
    String configModule = FIXTURE_TO_CONFIGMODULE[fixtureName]

    sendEvent(name: "fixtureMode", value: "spawning")

    def child
    try {
        child = addChildDevice("level99", driverName, virtualDni,
                               [label: childLabel ?: fixtureName, isComponent: false])
    } catch (Exception e) {
        logError "[DEV TOOL] addChildDevice failed for '${driverName}': ${e.message}. " +
                 "Ensure the driver '${driverName}' is installed on this hub."
        sendEvent(name: "fixtureMode", value: "ready")
        return
    }

    if (!child) {
        logError "[DEV TOOL] addChildDevice returned null for '${driverName}' — " +
                 "driver may not be installed."
        sendEvent(name: "fixtureMode", value: "ready")
        return
    }

    child.updateDataValue("deviceType", deviceType)
    child.updateDataValue("configModule", configModule)
    // cid and configModule in the data values — needed if child calls parent methods
    // that reference these (e.g. a self-fetch via update())
    child.updateDataValue("cid", virtualDni)

    // BP17 read->mutate->reassign pattern for state Maps
    Map currentBindings = new HashMap(state.childFixtures ?: [:])
    currentBindings[virtualDni] = fixtureName
    state.childFixtures = currentBindings

    Map currentSnapshots = new HashMap(state.fixtureSnapshots ?: [:])
    currentSnapshots[virtualDni] = canonicalDefaultState(fixtureName)
    state.fixtureSnapshots = currentSnapshots

    logInfo "[DEV TOOL] Spawned child ${virtualDni} bound to fixture ${fixtureName}"
    sendEvent(name: "lastFixtureLoaded", value: fixtureName)
    sendEvent(name: "fixtureMode", value: "ready")

    // Seed initial state: deliver status response to populate child attributes
    Map initialResponse = synthesizeStatusResponse(virtualDni, fixtureName)
    child.update(initialResponse, null)
}

// ---------------------------------------------------------------------------
// resetAllChildren
// ---------------------------------------------------------------------------

def resetAllChildren() {
    logDebug "resetAllChildren()"
    getChildDevices()?.each { child ->
        deleteChildDevice(child.deviceNetworkId)
    }
    state.childFixtures  = [:]
    state.fixtureSnapshots = [:]
    sendEvent(name: "fixtureMode", value: "ready")
    logInfo "[DEV TOOL] All virtual children removed."
}

// ---------------------------------------------------------------------------
// stepFixtureResponse (manual op-step for interactive testing)
// ---------------------------------------------------------------------------

def stepFixtureResponse(String childDni, String opName) {
    logDebug "stepFixtureResponse(${childDni}, ${opName})"
    ensureDebugWatchdog()
    runIn(1, "deliverFixtureResponse", [data: [dni: childDni, fixtureName: state.childFixtures?[childDni]]])
}

// ---------------------------------------------------------------------------
// sendBypassRequest — virtual cloud routing
//
// Children call parent.sendBypassRequest(device, payload, closure) to send
// commands. This virtual implementation:
//   1. Validates payload data keys against the fixture's canonical shape.
//   2. Updates the per-DNI canonical state snapshot from the request.
//   3. Schedules an async response delivery (mirrors real cloud latency).
// ---------------------------------------------------------------------------

def Boolean sendBypassRequest(equipment, payload, Closure closure) {
    if (!equipment) {
        logError "[DEV TOOL] sendBypassRequest called with null equipment"
        return false
    }

    Map payloadMap = payload as Map

    String dni = equipment?.deviceNetworkId ?: ""
    Map currentBindings = state.childFixtures ?: [:]
    String fixtureName = currentBindings[dni] as String

    if (!fixtureName) {
        logError "[DEV TOOL] sendBypassRequest from unbound child ${dni} — no fixture mapping. " +
                 "Spawn this child via spawnFromFixture() first."
        return false
    }

    String method = payloadMap?.method ?: payloadMap?.get("method") ?: "?"
    sendEvent(name: "lastSendBypassMethod", value: method)
    sendEvent(name: "lastFixtureLoaded",    value: fixtureName)

    if (settings?.verboseDebug) {
        logDebug "[DEV TOOL] sendBypassRequest method=${method} data=${payloadMap?.data}"
    }

    // Find canonical fixture op for this method
    Map fixtureOp = findFixtureOpByMethod(fixtureName, method)
    if (fixtureOp == null) {
        logWarn "[DEV TOOL] No fixture op for method '${method}' in ${fixtureName} — " +
                "payload not validated, skipping response."
        return true
    }

    // Validate payload data keys against fixture canonical shape
    Set ourKeys     = ((payloadMap?.data as Map)?.keySet() ?: []) as Set
    Set fixtureKeys = (fixtureOp.dataKeys as Set) ?: ([] as Set)
    if (ourKeys != fixtureKeys) {
        logError "[DEV TOOL] Payload data keys mismatch for method '${method}': " +
                 "ours=${ourKeys.sort()}, pyvesync=${fixtureKeys.sort()}"
    } else {
        logDebug "[DEV TOOL] Payload validated: ${method} keys=${ourKeys.sort()}"
    }

    // Update canonical state snapshot from this request
    updateCanonicalState(dni, method, payloadMap?.data as Map)

    // Deliver canned "success" response to the closure immediately.
    // The Core line's sendBypassRequest closure receives the raw HttpResponse.
    // We simulate an HTTP 200 OK with the bypassV2 success envelope.
    //
    // Using groovy.util.Expando instead of an inner class: Hubitat's Groovy sandbox
    // treats driver sources as Script subclasses and does not support inner class
    // declarations. Expando is sandbox-safe and supports the same property access
    // pattern (resp.status, resp.data) that Core 200S's checkHttpResponse() uses.
    def vr = new Expando()
    vr.status = 200
    vr.data   = [
        code: 0,
        msg: "request success",
        result: [
            code: 0,
            result: [:],
            traceId: "virtual-trace"
        ],
        traceId: "virtual-trace"
    ]
    try {
        closure(vr)
    } catch (Exception e) {
        logError "[DEV TOOL] Closure threw exception for method '${method}': ${e.message}"
    }

    // Async status refresh: deliver synthesized status response after 1s
    // so that any immediately-following attribute reads see updated values.
    runIn(1, "deliverFixtureResponse", [data: [dni: dni, fixtureName: fixtureName]])
    return true
}

// ---------------------------------------------------------------------------
// Async response delivery
// ---------------------------------------------------------------------------

void deliverFixtureResponse(Map data) {
    String dni = data?.dni as String
    String fixtureName = data?.fixtureName as String
    if (!dni || !fixtureName) return

    def child = getChildDevice(dni)
    if (!child) {
        logWarn "[DEV TOOL] deliverFixtureResponse: child ${dni} not found (removed?)"
        return
    }

    Map response = synthesizeStatusResponse(dni, fixtureName)
    logDebug "[DEV TOOL] deliverFixtureResponse: pushing status to ${dni}"
    child.update(response, null)
}

// ---------------------------------------------------------------------------
// Canonical state management
// ---------------------------------------------------------------------------

/**
 * Return a fresh deep copy of the default state for the named fixture.
 * Mutable — callers may mutate without affecting the constant.
 */
private Map canonicalDefaultState(String fixtureName) {
    switch (fixtureName) {
        case "Core200S":
            // Shallow copy first, then deep-copy any nested Maps so mutators
            // (e.g. addTimer writing to snap.extension.timer_remain) don't hit the
            // immutable nested reference from CORE_200S_DEFAULT_STATE.asImmutable().
            Map copy = new HashMap(CORE_200S_DEFAULT_STATE)
            copy.extension = new HashMap(CORE_200S_DEFAULT_STATE.extension ?: [:])
            return copy
        default:
            logWarn "[DEV TOOL] canonicalDefaultState: unknown fixture '${fixtureName}', returning empty map"
            return [:]
    }
}

/**
 * Apply the effect of an outbound command to the per-DNI state snapshot.
 * This keeps the synthesized status responses consistent with what the child
 * thinks the device's current state is.
 */
private void updateCanonicalState(String dni, String method, Map data) {
    Map currentSnapshots = state.fixtureSnapshots ?: [:]
    Map snap = new HashMap(currentSnapshots[dni] as Map ?: [:])
    Closure mutator = STATE_MUTATORS[method]
    if (mutator) {
        mutator(snap, data ?: [:])
    }
    // BP17 read->mutate->reassign
    Map updatedSnapshots = new HashMap(state.fixtureSnapshots ?: [:])
    updatedSnapshots[dni] = snap
    state.fixtureSnapshots = updatedSnapshots
}

/**
 * Build a synthetic getPurifierStatus / getHumidifierStatus response for
 * the given DNI, using the current canonical state snapshot.
 *
 * Shape matches what the Core 200S child's update(status, nightLight) expects:
 *   status.result.enabled, status.result.level, status.result.mode, etc.
 * This is the "inner result" envelope that the parent passes to update().
 */
Map synthesizeStatusResponse(String dni, String fixtureName) {
    Map currentSnapshots = state.fixtureSnapshots ?: [:]
    Map snap = currentSnapshots[dni] as Map ?: canonicalDefaultState(fixtureName)

    // Core-line envelope shape: { code:0, result: { <device fields> } }
    // The parent driver calls resp.data.result and passes it to update(status, nightLight).
    // update(status, nightLight) reads status.result.* (level, mode, enabled, etc.)
    // So we need: { code:0, result: { <device fields> } }
    // Which is what the child receives as `status` in update(status, nightLight).
    return [
        code  : 0,
        result: new HashMap(snap)
    ]
}

/**
 * Find the fixture op entry for the given method name.
 * Returns null if no matching op is found.
 */
private Map findFixtureOpByMethod(String fixtureName, String method) {
    List ops = FIXTURE_OPS[fixtureName] as List
    if (!ops) return null
    return ops.find { it.methodName == method } as Map
}

// ---------------------------------------------------------------------------
// Logging helpers
// ---------------------------------------------------------------------------

private void logInfo(msg) {
    if (settings?.descriptionTextEnable) log.info msg
}

private void logDebug(msg) {
    if (settings?.debugOutput) log.debug msg
}

// Virtual parent always surfaces warns/errors — no pref gate
private void logWarn(msg)  { log.warn  msg }
private void logError(msg) { log.error msg }

void logDebugOff() {
    if (settings?.debugOutput) device.updateSetting("debugOutput", [type:"bool", value:false])
}

// BP16: auto-disable stuck debugOutput after hub reboot
// Called from sendBypassRequest, spawnFromFixture, stepFixtureResponse, and refresh().
private void ensureDebugWatchdog() {
    if (settings?.debugOutput && state.debugEnabledAt) {
        Long elapsed = now() - (state.debugEnabledAt as Long)
        if (elapsed > 30 * 60 * 1000) {
            logInfo "BP16 watchdog: 30 min elapsed since debug enable; auto-disabling (post-reboot self-heal)"
            device.updateSetting("debugOutput", [type:"bool", value:false])
            state.remove("debugEnabledAt")
        }
    }
}
