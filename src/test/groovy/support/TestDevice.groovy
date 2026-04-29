package support

/**
 * Minimal Hubitat device proxy for driver unit tests.
 *
 * Drivers call `device.sendEvent(Map)`, `device.currentValue(String)`,
 * `device.updateSetting(String, Map)`, `device.getName()`, `device.getId()`.
 * This class captures those calls in memory so specs can assert on them.
 *
 * Events are appended to `events` in order. `currentValue` reads back from the
 * most-recently-sent event for the named attribute, mirroring Hubitat behaviour
 * (though Hubitat's version is async; here it is synchronous on the list — do
 * NOT call `currentValue` from inside `applyStatus` in production code; use
 * local variables per the Bug Pattern #7 guidance).
 *
 * Settings writes via `updateSetting(name, [type, value])` are stored in
 * `settingsUpdates` and also reflected back into the settings map the driver
 * reads from `settings?.foo` (the harness wires settings through `settings`
 * on the driver instance).
 */
class TestDevice {
    String id    = "test-device-001"
    String name  = "Test Device"
    String label = null  // mirrors real Hubitat: label overrides name in the UI; null means use name

    // Ordered list of all events sent via sendEvent during a test.
    // Specs assert on this: events.find { it.name == "switch" }?.value == "on"
    final List<Map> events = []

    // Ordered list of updateSetting calls: [[name: "foo", type: "bool", value: true], ...]
    final List<Map> settingsUpdates = []

    // Back-reference to the settings map (injected by HubitatSpec). When the driver
    // calls device.updateSetting("descriptionTextEnable", [type:"bool", value:true]),
    // we update both settingsUpdates and the live settings map so future
    // settings?.descriptionTextEnable reads see the committed value.
    Map settingsRef = [:]

    void sendEvent(Map args) {
        events << args.clone()
    }

    Object currentValue(String attr) {
        // Return the last value sent for this attribute name. Null if never set.
        def found = events.reverse().find { it.name == attr }
        return found?.value
    }

    void updateSetting(String settingName, Map args) {
        settingsUpdates << [name: settingName, type: args.type, value: args.value]
        // Reflect into the live settings map so subsequent settings?.foo reads
        // see the new value (heals Bug Pattern #12 pref-seed path in tests).
        settingsRef[settingName] = args.value
    }

    String getName() { name }
    String getId()   { id }

    // Hubitat devices also expose .typeName (used by parent's updateDevices() to
    // branch on humidifier vs purifier). Default to empty; specs that test the
    // parent driver's branching logic can override.
    String typeName = ""

    // Custom data values: used by parent's sendBypassRequest to retrieve cid and configModule.
    // The parent calls equipment.getDataValue("cid") and equipment.getDataValue("configModule").
    private Map<String,String> dataValues = [cid: "test-cid-001", configModule: "test-config-module"]

    String getDataValue(String key) {
        dataValues.getOrDefault(key, "")
    }

    void updateDataValue(String key, String value) {
        dataValues[key] = value
    }

    // Device network ID — used by parent's getDevices() cleanup loop.
    String deviceNetworkId = "test-device-001"
    String getDeviceNetworkId() { deviceNetworkId }

    /** Clear all captured events and settings updates. Called by HubitatSpec.setup(). */
    void reset() {
        events.clear()
        settingsUpdates.clear()
    }

    /** Convenience: find the latest event for an attribute. Returns null if none. */
    Map lastEvent(String attr) {
        events.reverse().find { it.name == attr }
    }

    /** Convenience: find all events for an attribute in emission order. */
    List<Map> allEvents(String attr) {
        events.findAll { it.name == attr }
    }
}
