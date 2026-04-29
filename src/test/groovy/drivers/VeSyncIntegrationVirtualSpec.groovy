package drivers

import support.HubitatSpec
import support.TestDevice

/**
 * Unit tests for VeSyncIntegrationVirtual.groovy (virtual test parent driver).
 *
 * Covers:
 *   1. spawnFromFixture creates child device with correct DNI, label, driver name,
 *      deviceType, and configModule data values.
 *   2. spawnFromFixture is blocked when state.realParentDetected=true — child not
 *      created; fixtureMode attribute set to "blocked-real-parent-installed".
 *   3. sendBypassRequest with matching payload keys logs DEBUG (not WARN/ERROR)
 *      and triggers deliverFixtureResponse.
 *   4. sendBypassRequest with mismatched data keys logs ERROR containing key diff.
 *   5. sendBypassRequest from an unbound child logs ERROR.
 *   6. resetAllChildren removes all children and clears state.childFixtures.
 *   7. synthesizeStatusResponse returns bypassV2 envelope shape with accumulated
 *      state mutations applied.
 *
 * Architecture note:
 *   The virtual parent driver is loaded as a Groovy script (same as real drivers).
 *   sendBypassRequest constructs a `groovy.util.Expando` as a sandbox-safe stand-in
 *   for the HttpResponseDecorator that children's closures receive in production
 *   (Hubitat's Groovy sandbox does not support inner class declarations in driver
 *   scripts). Expando supports the same property-access pattern (`resp.status`,
 *   `resp.data`) that Core 200S's checkHttpResponse() uses.
 *
 *   The driver uses `addChildDevice`, `getChildDevice`, `getChildDevices`, and
 *   `deleteChildDevice` — Hubitat sandbox globals. We wire these via metaClass to
 *   operate on a local Map of TestDevice instances.
 */
class VeSyncIntegrationVirtualSpec extends HubitatSpec {

    // In-memory child device registry: DNI -> TestDevice
    // Wired into the driver via metaClass overrides of addChildDevice/getChildDevice/etc.
    Map<String, TestDevice> childRegistry = [:]

    @Override
    String driverSourcePath() {
        "Drivers/Levoit/VeSyncIntegrationVirtual.groovy"
    }

    @Override
    Map defaultSettings() {
        return [
            descriptionTextEnable: true,
            debugOutput: true,       // on so we can assert log.debug calls
            verboseDebug: false
        ]
    }

    @Override
    void wireSandbox(def driverInstance) {
        super.wireSandbox(driverInstance)

        def mc = driverInstance.metaClass

        // Override runIn to be a no-op (base HubitatSpec registers it, but we also need
        // to accept the [data: ...] overload used by the virtual parent's deliverFixtureResponse call)
        mc.runIn = { Object[] args ->
            // no-op: don't actually schedule; tests call deliverFixtureResponse manually
        }

        // Wire child device management APIs
        mc.addChildDevice = { String namespace, String driverName, String dni, Map options = [:] ->
            def child = new TestDevice()
            child.deviceNetworkId = dni
            child.name = driverName
            child.label = options?.label ?: driverName
            childRegistry[dni] = child
            return child
        }

        mc.getChildDevice = { String dni ->
            childRegistry[dni]
        }

        mc.getChildDevices = { ->
            childRegistry.values().toList()
        }

        mc.deleteChildDevice = { String dni ->
            childRegistry.remove(dni)
        }

        // Wire sendEvent to also record in testDevice (already done by super) and
        // allow device.updateSetting to work correctly.
        // Note: the driver calls `sendEvent(name:..., value:...)` at top-level (not device.sendEvent)
        // HubitatSpec.wireSandbox already handles this via mc.sendEvent = { ... }
    }

    def setup() {
        // Closures wired in HubitatSpec.setup() reference `childRegistry` as a
        // field on this spec instance (Groovy resolves at call-time via property
        // dispatch, not capture-time). Reassigning the field here gives each
        // test a fresh empty registry without needing to re-wire the metaClass.
        childRegistry = [:]
    }

    // -------------------------------------------------------------------------
    // Shared fixture helper
    // -------------------------------------------------------------------------

    private Map makeCore200SStatus(boolean enabled = true, int level = 1,
                                   String mode = "manual", int filterLife = 80) {
        return [
            code  : 0,
            result: [
                enabled    : enabled,
                level      : level,
                mode       : mode,
                filter_life: filterLife,
                night_light: "on",
                display    : true,
                child_lock : false,
                extension  : [timer_remain: 0]
            ]
        ]
    }

    // -------------------------------------------------------------------------
    // Test 1: spawnFromFixture creates child with correct metadata
    // -------------------------------------------------------------------------

    def "spawnFromFixture Core200S creates child with correct DNI, label, driver name, deviceType, configModule"() {
        given: "no real parent present"
        state.realParentDetected = false

        when:
        driver.spawnFromFixture("Core200S", "Test Air Purifier")

        then: "child device was created with the virtual DNI convention"
        def expectedDni = "VirtualVeSync-Core200S-Test-Air-Purifier"
        childRegistry.containsKey(expectedDni)

        and: "child label matches the requested label"
        childRegistry[expectedDni].label == "Test Air Purifier"

        and: "child driver name is the exact frozen name for Core 200S"
        childRegistry[expectedDni].name == "Levoit Core200S Air Purifier"

        and: "child deviceType data value is set"
        childRegistry[expectedDni].getDataValue("deviceType") == "Core200S"

        and: "child configModule data value is set to the pyvesync canonical value"
        childRegistry[expectedDni].getDataValue("configModule") == "WifiBTOnboardingNotificationsCore200S"

        and: "state.childFixtures records the binding"
        state.childFixtures[expectedDni] == "Core200S"

        and: "state.fixtureSnapshots has a default-state entry for the DNI"
        state.fixtureSnapshots[expectedDni] != null
        state.fixtureSnapshots[expectedDni].enabled == true
        state.fixtureSnapshots[expectedDni].level   == 1
        state.fixtureSnapshots[expectedDni].mode    == "manual"

        and: "no ERROR logs"
        testLog.errors.isEmpty()
    }

    def "spawnFromFixture emits fixtureMode=ready after successful spawn"() {
        given:
        state.realParentDetected = false

        when:
        driver.spawnFromFixture("Core200S", "MyPurifier")

        then:
        lastEventValue("fixtureMode") == "ready"
    }

    def "spawnFromFixture emits lastFixtureLoaded=Core200S after successful spawn"() {
        given:
        state.realParentDetected = false

        when:
        driver.spawnFromFixture("Core200S", "MyPurifier")

        then:
        lastEventValue("lastFixtureLoaded") == "Core200S"
    }

    // -------------------------------------------------------------------------
    // Test 2: spawnFromFixture blocked when real parent detected
    // -------------------------------------------------------------------------

    def "spawnFromFixture blocked when state.realParentDetected=true — no child created"() {
        given: "real parent simulated via state flag"
        state.realParentDetected = true

        when:
        driver.spawnFromFixture("Core200S", "ShouldNotSpawn")

        then: "no child device created"
        childRegistry.isEmpty()

        and: "fixtureMode reflects blocked state"
        lastEventValue("fixtureMode") == "blocked-real-parent-installed"

        and: "state.childFixtures not populated"
        (state.childFixtures ?: [:]).isEmpty()
    }

    def "spawnFromFixture blocked when detectRealParent heuristic fires (non-virtual child exists)"() {
        given: "a pre-existing non-virtual child device"
        def realChild = new TestDevice()
        realChild.deviceNetworkId = "some-real-vesync-device-cid"
        childRegistry["some-real-vesync-device-cid"] = realChild

        // Don't set state.realParentDetected — let heuristic fire
        state.realParentDetected = null

        when:
        driver.spawnFromFixture("Core200S", "ShouldBeBlocked")

        then: "blocked"
        lastEventValue("fixtureMode") == "blocked-real-parent-installed"

        and: "no additional children created beyond the pre-existing one"
        childRegistry.size() == 1
    }

    // -------------------------------------------------------------------------
    // Test 3: sendBypassRequest with matching keys logs DEBUG not ERROR
    // -------------------------------------------------------------------------

    def "sendBypassRequest with matching setSwitch keys logs DEBUG (not WARN/ERROR)"() {
        given: "child bound to Core200S fixture"
        state.realParentDetected = false
        driver.spawnFromFixture("Core200S", "PurifierA")
        String dni = "VirtualVeSync-Core200S-PurifierA"
        testLog.reset()  // clear spawn-time logs

        def childDevice = childRegistry[dni]
        settings.debugOutput = true

        when: "send setSwitch with canonical keys: [enabled, id]"
        def result = driver.sendBypassRequest(childDevice, [
            method: "setSwitch",
            source: "APP",
            data  : [enabled: false, id: 0]
        ], { resp -> /* no-op closure */ })

        then: "no ERROR logged"
        testLog.errors.isEmpty()

        and: "no WARN logged for key mismatch"
        !testLog.warns.any { it.contains("mismatch") }

        and: "DEBUG message logged confirming payload validated"
        testLog.debugs.any { it.contains("Payload validated") && it.contains("setSwitch") }

        and: "result is true (success)"
        result == true
    }

    def "sendBypassRequest with matching setLevel keys logs DEBUG (not WARN/ERROR)"() {
        given:
        state.realParentDetected = false
        driver.spawnFromFixture("Core200S", "PurifierB")
        String dni = "VirtualVeSync-Core200S-PurifierB"
        testLog.reset()
        def child = childRegistry[dni]
        settings.debugOutput = true

        when: "send setLevel with canonical keys: [level, id, type]"
        driver.sendBypassRequest(child, [
            method: "setLevel",
            source: "APP",
            data  : [level: 3, id: 0, type: "wind"]
        ], { resp -> /* closure */ })

        then:
        testLog.errors.isEmpty()
        testLog.debugs.any { it.contains("Payload validated") && it.contains("setLevel") }
    }

    // -------------------------------------------------------------------------
    // Test 4: sendBypassRequest with mismatched keys logs ERROR
    // -------------------------------------------------------------------------

    def "sendBypassRequest with mismatched data keys logs ERROR with key diff"() {
        given:
        state.realParentDetected = false
        driver.spawnFromFixture("Core200S", "PurifierC")
        String dni = "VirtualVeSync-Core200S-PurifierC"
        testLog.reset()
        def child = childRegistry[dni]

        when: "send setSwitch but with a wrong key name (powerSwitch instead of enabled)"
        driver.sendBypassRequest(child, [
            method: "setSwitch",
            source: "APP",
            data  : [powerSwitch: 0, switchIdx: 0]  // V2-line keys, wrong for Core line
        ], { resp -> /* closure */ })

        then: "ERROR logged containing key diff info"
        testLog.errors.any { it.contains("mismatch") && it.contains("setSwitch") }

        and: "ERROR message contains our actual keys"
        testLog.errors.any { it.contains("powerSwitch") || it.contains("switchIdx") }
    }

    // -------------------------------------------------------------------------
    // Test 5: sendBypassRequest from unbound child logs ERROR
    // -------------------------------------------------------------------------

    def "sendBypassRequest from unbound child logs ERROR"() {
        given: "a child device that was never spawned via spawnFromFixture"
        def unboundChild = new TestDevice()
        unboundChild.deviceNetworkId = "some-unbound-device-dni"

        when:
        driver.sendBypassRequest(unboundChild, [
            method: "setSwitch",
            source: "APP",
            data  : [enabled: true, id: 0]
        ], { resp -> /* closure */ })

        then: "ERROR logged about unbound child"
        testLog.errors.any { it.contains("unbound") || it.contains("no fixture mapping") }
    }

    // -------------------------------------------------------------------------
    // Test 6: resetAllChildren
    // -------------------------------------------------------------------------

    def "resetAllChildren removes all spawned children and clears state.childFixtures"() {
        given: "two children spawned"
        state.realParentDetected = false
        driver.spawnFromFixture("Core200S", "Purifier1")
        driver.spawnFromFixture("Core200S", "Purifier2")
        assert childRegistry.size() == 2
        assert (state.childFixtures ?: [:]).size() == 2

        when:
        driver.resetAllChildren()

        then: "child registry empty"
        childRegistry.isEmpty()

        and: "state.childFixtures cleared"
        (state.childFixtures ?: [:]).isEmpty()

        and: "fixtureMode reset to ready"
        lastEventValue("fixtureMode") == "ready"

        and: "no errors"
        testLog.errors.isEmpty()
    }

    // -------------------------------------------------------------------------
    // Test 7: synthesizeStatusResponse returns correct envelope + state mutations
    // -------------------------------------------------------------------------

    def "synthesizeStatusResponse returns Core200S default state in correct envelope"() {
        given:
        state.realParentDetected = false
        driver.spawnFromFixture("Core200S", "PurifierD")
        String dni = "VirtualVeSync-Core200S-PurifierD"

        when:
        Map response = driver.synthesizeStatusResponse(dni, "Core200S")

        then: "outer envelope has code:0"
        response.code == 0

        and: "result map contains Core200S device fields"
        response.result != null
        response.result.enabled == true
        response.result.level   == 1
        response.result.mode    == "manual"
        response.result.filter_life == 80
    }

    def "synthesizeStatusResponse reflects setSwitch mutation (enabled=false)"() {
        given:
        state.realParentDetected = false
        driver.spawnFromFixture("Core200S", "PurifierE")
        String dni = "VirtualVeSync-Core200S-PurifierE"
        def child = childRegistry[dni]
        testLog.reset()

        when: "send setSwitch(enabled=false)"
        driver.sendBypassRequest(child, [
            method: "setSwitch",
            source: "APP",
            data  : [enabled: false, id: 0]
        ], { resp -> /* closure */ })

        then: "canonical snapshot now has enabled=false"
        state.fixtureSnapshots[dni]?.enabled == false

        and: "synthesizeStatusResponse reflects mutation"
        def response = driver.synthesizeStatusResponse(dni, "Core200S")
        response.result.enabled == false
    }

    def "synthesizeStatusResponse reflects setLevel mutation (level=3, mode=manual)"() {
        given:
        state.realParentDetected = false
        driver.spawnFromFixture("Core200S", "PurifierF")
        String dni = "VirtualVeSync-Core200S-PurifierF"
        def child = childRegistry[dni]
        testLog.reset()

        when: "send setLevel(level=3)"
        driver.sendBypassRequest(child, [
            method: "setLevel",
            source: "APP",
            data  : [level: 3, id: 0, type: "wind"]
        ], { resp -> /* closure */ })

        then: "canonical snapshot updated"
        state.fixtureSnapshots[dni]?.level == 3
        state.fixtureSnapshots[dni]?.mode  == "manual"

        and: "response reflects mutation"
        def response = driver.synthesizeStatusResponse(dni, "Core200S")
        response.result.level == 3
    }

    def "synthesizeStatusResponse reflects setPurifierMode mutation (mode=sleep)"() {
        given:
        state.realParentDetected = false
        driver.spawnFromFixture("Core200S", "PurifierG")
        String dni = "VirtualVeSync-Core200S-PurifierG"
        def child = childRegistry[dni]
        testLog.reset()

        when: "send setPurifierMode(mode=sleep)"
        driver.sendBypassRequest(child, [
            method: "setPurifierMode",
            source: "APP",
            data  : [mode: "sleep"]
        ], { resp -> /* closure */ })

        then: "snapshot mode updated"
        state.fixtureSnapshots[dni]?.mode == "sleep"
    }

    def "synthesizeStatusResponse reflects resetFilter mutation (filter_life=100)"() {
        given:
        state.realParentDetected = false
        driver.spawnFromFixture("Core200S", "PurifierH")
        String dni = "VirtualVeSync-Core200S-PurifierH"
        // Manually lower filter life in snapshot
        Map snaps = new HashMap(state.fixtureSnapshots ?: [:])
        Map snap = new HashMap(snaps[dni] ?: [:])
        snap.filter_life = 8
        snaps[dni] = snap
        state.fixtureSnapshots = snaps

        def child = childRegistry[dni]
        testLog.reset()

        when: "send resetFilter"
        driver.sendBypassRequest(child, [
            method: "resetFilter",
            source: "APP",
            data  : [:]
        ], { resp -> /* closure */ })

        then: "filter life reset to 100"
        state.fixtureSnapshots[dni]?.filter_life == 100
    }

    // -------------------------------------------------------------------------
    // BP12: pref-seed fires in updated()
    // -------------------------------------------------------------------------

    def "pref-seed fires in updated() when descriptionTextEnable is null (BP12)"() {
        given:
        settings.descriptionTextEnable = null
        state.prefsSeeded = null

        when:
        driver.updated()

        then:
        def seedCall = testDevice.settingsUpdates.find { it.name == "descriptionTextEnable" }
        seedCall != null
        seedCall.value == true
        state.prefsSeeded == true
    }

    def "pref-seed does NOT overwrite user-set false in updated() (BP12)"() {
        given:
        settings.descriptionTextEnable = false
        state.prefsSeeded = null

        when:
        driver.updated()

        then:
        def seedCall = testDevice.settingsUpdates.find { it.name == "descriptionTextEnable" }
        seedCall == null
    }

    // -------------------------------------------------------------------------
    // BP16: ensureDebugWatchdog auto-disables stuck debugOutput
    // -------------------------------------------------------------------------

    def "ensureDebugWatchdog disables stuck debugOutput after 30 min (BP16)"() {
        given: "debug enabled 35 min ago"
        settings.debugOutput = true
        settings.descriptionTextEnable = true
        state.debugEnabledAt = driver.now() - (35 * 60 * 1000)

        when:
        driver.ensureDebugWatchdog()

        then: "debug disabled"
        def debugCall = testDevice.settingsUpdates.find { it.name == "debugOutput" }
        debugCall != null
        debugCall.value == false

        and: "state.debugEnabledAt cleared"
        !state.containsKey("debugEnabledAt")
    }

    def "ensureDebugWatchdog no-ops within 30 min window (BP16)"() {
        given: "debug enabled 5 min ago"
        settings.debugOutput = true
        state.debugEnabledAt = driver.now() - (5 * 60 * 1000)

        when:
        driver.ensureDebugWatchdog()

        then: "no updateSetting call"
        testDevice.settingsUpdates.find { it.name == "debugOutput" } == null
    }

    // -------------------------------------------------------------------------
    // sendBypassRequest: closure receives Expando response with status=200
    // -------------------------------------------------------------------------

    def "sendBypassRequest closure receives a response with status 200"() {
        given:
        state.realParentDetected = false
        driver.spawnFromFixture("Core200S", "PurifierClosure")
        String dni = "VirtualVeSync-Core200S-PurifierClosure"
        def child = childRegistry[dni]
        testLog.reset()

        int capturedStatus = -1

        when:
        driver.sendBypassRequest(child, [
            method: "setSwitch",
            source: "APP",
            data  : [enabled: true, id: 0]
        ], { resp ->
            capturedStatus = resp.status
        })

        then:
        capturedStatus == 200
    }

    // -------------------------------------------------------------------------
    // sendBypassRequest: unknown method logs WARN but does NOT log ERROR
    // -------------------------------------------------------------------------

    def "sendBypassRequest with unknown method logs WARN but not ERROR"() {
        given:
        state.realParentDetected = false
        driver.spawnFromFixture("Core200S", "PurifierUnknown")
        def child = childRegistry["VirtualVeSync-Core200S-PurifierUnknown"]
        testLog.reset()

        when:
        driver.sendBypassRequest(child, [
            method: "someUnknownMethod",
            source: "APP",
            data  : [foo: "bar"]
        ], { resp -> /* closure */ })

        then: "WARN logged (fixture op not found)"
        testLog.warns.any { it.contains("No fixture op") || it.contains("someUnknownMethod") }

        and: "no ERROR for unknown method (only WARN)"
        !testLog.errors.any { it.contains("someUnknownMethod") }
    }

    // -------------------------------------------------------------------------
    // BLOCKING #4: detectRealParent false-negative — known limitation test
    // -------------------------------------------------------------------------

    def "detectRealParent returns false when getChildDevices is empty and no flag set (known false-negative limitation)"() {
        // KNOWN LIMITATION: detectRealParent() cannot detect a real VeSync Integration
        // parent that has not yet spawned any children. If the real parent is installed
        // but Resync Equipment has not been clicked, this returns false and spawnFromFixture()
        // will proceed — potentially putting virtual and real children on the same hub.
        // Users must fully uninstall the real parent before using the virtual test parent.
        // This test documents the gap; it is NOT asserting correct safety behavior.
        given: "no state flag and no children in the registry (empty hub)"
        state.realParentDetected = null
        assert childRegistry.isEmpty()

        when:
        boolean result = driver.detectRealParent()

        then: "known false-negative: returns false even though real parent may be installed"
        result == false
    }

    // -------------------------------------------------------------------------
    // BLOCKING #5: addTimer via sendBypassRequest uses canonicalDefaultState path
    // Exercises the deep-copy fix — would throw UnsupportedOperationException
    // without it, because CORE_200S_DEFAULT_STATE.extension is immutable.
    // -------------------------------------------------------------------------

    def "addTimer via sendBypassRequest does not throw when called via canonicalDefaultState path"() {
        given: "child spawned fresh (uses canonicalDefaultState, not patched snapshot)"
        state.realParentDetected = false
        driver.spawnFromFixture("Core200S", "PurifierAddTimer")
        String dni = "VirtualVeSync-Core200S-PurifierAddTimer"
        def child = childRegistry[dni]
        testLog.reset()

        when: "send addTimer — mutator writes to snap.extension.timer_remain"
        driver.sendBypassRequest(child, [
            method: "addTimer",
            source: "APP",
            data  : [action: "off", total: 3600]
        ], { resp -> /* closure */ })

        then: "no exception thrown (UnsupportedOperationException would fire here without deep-copy fix)"
        noExceptionThrown()

        and: "canonical snapshot reflects timer set"
        state.fixtureSnapshots[dni]?.extension?.timer_remain == 3600

        and: "no errors logged"
        testLog.errors.isEmpty()
    }
}
