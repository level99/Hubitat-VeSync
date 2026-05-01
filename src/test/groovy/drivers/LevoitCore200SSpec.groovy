package drivers

import support.HubitatSpec

/**
 * Unit tests for LevoitCore200S.groovy (Levoit Core 200S Air Purifier).
 *
 * Covers:
 *   Bug Pattern #1  — 2-arg update(status, nightLight) signature exists
 *   Bug Pattern #12 — pref-seed in update(status, nightLight)
 *   Bug Pattern #16 — debug auto-disable watchdog (child-side canonical test)
 *   Happy path      — update(status, nightLight) parses Core 200S response correctly
 *
 * NOTE: The Core 200S uses an OLDER driver structure. Unlike Vital 200S,
 * it does NOT have a separate applyStatus() method — status parsing happens
 * directly in update(status, nightLight). The pref-seed block and all state
 * mutations are in that method.
 *
 * The response format is also different from V2:
 *   - status.result.level (Integer, 1-3) for fan speed
 *   - status.result.mode (String: "manual" | "sleep")
 *   - status.result.enabled (Boolean)
 *   - status.result.filter_life (Integer, 0-100)
 *   - status.result.night_light (String: "on" | "off" | "dim")
 */
class LevoitCore200SSpec extends HubitatSpec {

    @Override
    String driverSourcePath() {
        "Drivers/Levoit/LevoitCore200S.groovy"
    }

    @Override
    Map defaultSettings() {
        return [descriptionTextEnable: null, debugOutput: false]
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #1: 2-arg update signature
    // -------------------------------------------------------------------------

    def "update(status, nightLight) 2-arg signature is callable (Bug Pattern #1)"() {
        given: "Core 200S status response with result nested under result key"
        def fixture = loadYamlFixture("Core200S.yaml")
        def status = fixture.responses.device_on_manual_speed2 as Map

        when: "parent calls update(status, nightLight)"
        def result = driver.update(status, null)

        then:
        noExceptionThrown()
        // Returns the parsed status map
        result != null
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #12: pref-seed (lives in update, not applyStatus for Core 200S)
    // -------------------------------------------------------------------------

    def "pref-seed fires in update() when descriptionTextEnable is null (Bug Pattern #12)"() {
        given: "settings has descriptionTextEnable=null"
        settings.descriptionTextEnable = null
        assert !state.prefsSeeded

        def fixture = loadYamlFixture("Core200S.yaml")
        def status = fixture.responses.device_on_manual_speed2 as Map

        when:
        driver.update(status, null)

        then: "updateSetting called to seed descriptionTextEnable=true"
        def seedCall = testDevice.settingsUpdates.find { it.name == "descriptionTextEnable" }
        seedCall != null
        seedCall.value == true
        state.prefsSeeded == true
    }

    def "pref-seed does NOT overwrite user-set false (Bug Pattern #12)"() {
        given:
        settings.descriptionTextEnable = false

        def fixture = loadYamlFixture("Core200S.yaml")
        def status = fixture.responses.device_on_manual_speed2 as Map

        when:
        driver.update(status, null)

        then:
        def seedCall = testDevice.settingsUpdates.find { it.name == "descriptionTextEnable" }
        seedCall == null
        state.prefsSeeded == true
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    def "update(status, nightLight) happy path from device_on_manual_speed2 fixture"() {
        given:
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("Core200S.yaml")
        def status = fixture.responses.device_on_manual_speed2 as Map
        // status structure: { result: { level:2, mode:"manual", enabled:true, filter_life:85, ... }, code:0 }
        assert status.result != null
        assert status.result.enabled == true

        when:
        driver.update(status, null)

        then: "switch is on"
        lastEventValue("switch") == "on"

        and: "mode is manual"
        lastEventValue("mode") == "manual"

        and: "filter is 85"
        lastEventValue("filter") == 85

        and: "speed is set from level 2 -> 'medium'"
        lastEventValue("speed") == "medium"

        and: "no errors logged"
        testLog.errors.isEmpty()
    }

    def "update(status, nightLight) happy path from device_off fixture"() {
        given:
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("Core200S.yaml")
        def status = fixture.responses.device_off as Map
        assert status.result.enabled == false

        when:
        driver.update(status, null)

        then: "switch is off"
        lastEventValue("switch") == "off"

        and: "no errors logged"
        testLog.errors.isEmpty()
    }

    def "filter life threshold INFO logs when below 10 percent"() {
        given: "prior filter life was 15 (above critical)"
        settings.descriptionTextEnable = true
        state.lastFilterLife = 15

        def fixture = loadYamlFixture("Core200S.yaml")
        def status = fixture.responses.device_filter_low as Map  // filter_life=8

        when:
        driver.update(status, null)

        then: "INFO logged about low filter"
        testLog.infos.any { it.contains("Filter") || it.contains("filter") || it.contains("8") }
    }

    def "speed mapIntegerToSpeed level=2 maps to 'medium'"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("Core200S.yaml")
        def status = fixture.responses.device_on_manual_speed2 as Map  // level=2

        when:
        driver.update(status, null)

        then: "speed is 'medium' for level 2"
        lastEventValue("speed") == "medium"
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #16: debug auto-disable watchdog (child-side canonical test)
    // This child is the canonical representative — other children carry identical
    // ensureDebugWatchdog() code so are not individually specced for BP16.
    // -------------------------------------------------------------------------

    def "ensureDebugWatchdog() no-ops when debugOutput is false (BP16 -- child guard)"() {
        given: "debug is off; timestamp from an old session is present"
        settings.debugOutput = false
        state.debugEnabledAt = driver.now() - (2 * 60 * 60 * 1000)  // 2 hours ago

        when:
        driver.ensureDebugWatchdog()

        then: "no updateSetting call"
        def debugCall = testDevice.settingsUpdates.find { it.name == "debugOutput" }
        debugCall == null
    }

    def "ensureDebugWatchdog() no-ops when elapsed < 30 min (BP16 -- within window)"() {
        given: "debug enabled 5 min ago"
        settings.debugOutput = true
        settings.descriptionTextEnable = true
        state.debugEnabledAt = driver.now() - (5 * 60 * 1000)  // 5 min ago

        when:
        driver.ensureDebugWatchdog()

        then: "watchdog did not fire"
        def debugCall = testDevice.settingsUpdates.find { it.name == "debugOutput" }
        debugCall == null
    }

    def "ensureDebugWatchdog() disables debug when elapsed >= 30 min (BP16 -- post-reboot self-heal)"() {
        given: "debug was enabled 35 min ago (runIn timer evaporated across reboot)"
        settings.debugOutput = true
        settings.descriptionTextEnable = true
        state.debugEnabledAt = driver.now() - (35 * 60 * 1000)  // 35 min ago

        when:
        driver.ensureDebugWatchdog()

        then: "updateSetting called to disable debugOutput"
        def debugCall = testDevice.settingsUpdates.find { it.name == "debugOutput" }
        debugCall != null
        debugCall.value == false

        and: "state.debugEnabledAt cleared"
        !state.containsKey("debugEnabledAt")

        and: "BP16 INFO log emitted"
        testLog.infos.any { it.contains("BP16 watchdog") }
    }

    def "update(status, nightLight) calls ensureDebugWatchdog() on every parent poll (BP16 -- call site)"() {
        given: "debug stuck true for 40 min (post-reboot scenario)"
        settings.debugOutput = true
        settings.descriptionTextEnable = true
        state.debugEnabledAt = driver.now() - (40 * 60 * 1000)  // 40 min ago

        def fixture = loadYamlFixture("Core200S.yaml")
        def status = fixture.responses.device_on_manual_speed2 as Map

        when: "parent calls update(status, nightLight)"
        driver.update(status, null)

        then: "watchdog fired inside update(): debug disabled"
        def debugCall = testDevice.settingsUpdates.find { it.name == "debugOutput" }
        debugCall != null
        debugCall.value == false

        and: "BP16 INFO log emitted"
        testLog.infos.any { it.contains("BP16 watchdog") }
    }

    // -------------------------------------------------------------------------
    // v2.3 new features: childLock, display, timer, resetFilter
    // -------------------------------------------------------------------------

    def "setChildLock('on') sends setChildLock with child_lock=true"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setChildLock("on")

        then: "setChildLock request sent with child_lock=true"
        def req = testParent.allRequests.find { it.method == "setChildLock" }
        req != null
        req.data.child_lock == true
    }

    def "setChildLock('off') sends setChildLock with child_lock=false"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setChildLock("off")

        then:
        def req = testParent.allRequests.find { it.method == "setChildLock" }
        req != null
        req.data.child_lock == false
    }

    def "update() parses child_lock=true from fixture and emits childLock='on'"() {
        given:
        settings.descriptionTextEnable = true
        // device_on_manual_speed1 has child_lock: true
        def fixture = loadYamlFixture("Core200S.yaml")
        def status = fixture.responses.device_on_manual_speed1 as Map
        assert status.result.child_lock == true

        when:
        driver.update(status, null)

        then:
        lastEventValue("childLock") == "on"
    }

    def "setTimer(300) sends addTimer with action='off' and total=300"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setTimer(300)

        then:
        def req = testParent.allRequests.find { it.method == "addTimer" }
        req != null
        req.data.action == "off"
        req.data.total == 300
    }

    def "resetFilter sends resetFilter method with empty data"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.resetFilter()

        then:
        def req = testParent.allRequests.find { it.method == "resetFilter" }
        req != null
        (req.data == null || req.data == [:])
    }

    // -------------------------------------------------------------------------
    // SwitchLevel 2-arg overload
    // -------------------------------------------------------------------------

    def "setLevel(50) 1-arg sends Core-line setLevel API call"() {
        // Baseline: confirm 1-arg setLevel routes to the speed API.
        given: "device is on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when: "setLevel(50) is called -- maps to speed 2 (33 <= 50 < 66)"
        driver.setLevel(50)

        then: "Core-line setLevel request was sent with {level, id, type}"
        noExceptionThrown()
        def req = testParent.allRequests.find { it.method == "setLevel" }
        req != null
        req.data.containsKey("level")
    }

    def "setLevel(50, 30) 2-arg form delegates to 1-arg (SwitchLevel standard signature)"() {
        // Hubitat SwitchLevel capability advertises setLevel(level, duration). Without the 2-arg
        // overload, callers (Rule Machine with duration, dashboards, MCP) throw MissingMethodException.
        given: "device is on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when: "setLevel is called with two args (level=50, duration=30)"
        driver.setLevel(50, 30)

        then: "same API call as setLevel(50) — Core-line setLevel request sent"
        noExceptionThrown()
        def req = testParent.allRequests.find { it.method == "setLevel" }
        req != null
        req.data.containsKey("level")
    }
}
