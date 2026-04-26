package drivers

import support.HubitatSpec
import support.TestParent

/**
 * Unit tests for LevoitGeneric.groovy (Levoit Generic Device — fall-through diagnostic driver).
 *
 * Covers:
 *   Bug Pattern #1  — 2-arg update(status, nightLight) signature exists and is callable
 *   Bug Pattern #3  — envelope peel handles both single-wrap (purifier) and double-wrap (humidifier)
 *   Bug Pattern #12 — pref-seed fires when descriptionTextEnable is null, preserves false
 *   Happy path (purifier shape) — heuristic field parsing emits expected events for purifier response
 *   Happy path (humidifier shape) — heuristic field parsing emits expected events for humidifier response
 *   Unknown shape   — unknown-shape response sets compat="unknown shape" and doesn't throw
 *   compat detection — detectShape correctly classifies purifier vs humidifier vs unknown
 *   captureDiagnostics — command produces well-formed markdown with all expected sections
 *   Power fallback  — on() tries setSwitch first; tests that setSwitch request is made
 */
class LevoitGenericSpec extends HubitatSpec {

    @Override
    String driverSourcePath() {
        "Drivers/Levoit/LevoitGeneric.groovy"
    }

    @Override
    Map defaultSettings() {
        // descriptionTextEnable null simulates first-install-before-Save state
        return [descriptionTextEnable: null, debugOutput: false]
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #1: 2-arg update signature
    // -------------------------------------------------------------------------

    def "update(status, nightLight) 2-arg signature is callable (Bug Pattern #1)"() {
        given: "a single-wrapped purifier-shape status response"
        def fixture = loadYamlFixture("LevoitGeneric.yaml")
        def deviceData = fixture.responses.purifier_on_manual as Map
        def status = purifierStatusEnvelope(deviceData)

        when: "parent calls update(status, nightLight) with null nightLight"
        def result = driver.update(status, null)

        then: "method exists and returns without throwing MissingMethodException"
        result == true
        noExceptionThrown()
    }

    def "update(status) 1-arg signature is callable"() {
        given: "a single-wrapped status response"
        def fixture = loadYamlFixture("LevoitGeneric.yaml")
        def status = purifierStatusEnvelope(fixture.responses.purifier_on_manual as Map)

        when:
        def result = driver.update(status)

        then:
        result == true
        noExceptionThrown()
    }

    def "update() 0-arg signature exists (calls into self-fetch path without throwing)"() {
        // The 0-arg update() calls hubBypass which calls parent.sendBypassRequest.
        // In the test harness, TestParent.sendBypassRequest returns an ok response
        // with no device fields; update() should complete without throwing.
        given: "no prior state"

        when: "0-arg update() is called"
        driver.update()

        then: "no exception thrown"
        noExceptionThrown()
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #3: envelope peel — single-wrap and double-wrap
    // -------------------------------------------------------------------------

    def "applyStatus peels single-wrap envelope to reach purifier device fields (Bug Pattern #3)"() {
        given: "a single-wrapped purifier status response"
        def fixture = loadYamlFixture("LevoitGeneric.yaml")
        def deviceData = fixture.responses.purifier_on_manual as Map
        def status = purifierStatusEnvelope(deviceData)

        when: "applyStatus is called with the single-wrapped envelope"
        driver.applyStatus(status)

        then: "device fields were reached — switch=on and PM25 emitted confirms dereference worked"
        lastEventValue("switch") == "on"
        lastEventValue("airQuality") != null
    }

    def "applyStatus peels double-wrap envelope to reach humidifier device fields (Bug Pattern #3)"() {
        given: "a double-wrapped humidifier status response"
        def fixture = loadYamlFixture("LevoitGeneric.yaml")
        def deviceData = fixture.responses.humidifier_on_auto as Map
        def status = humidifierStatusEnvelope(deviceData)

        when: "applyStatus is called with the double-wrapped envelope"
        driver.applyStatus(status)

        then: "device fields were reached — humidity event confirms peel worked"
        lastEventValue("humidity") != null
        lastEventValue("switch") == "on"
    }

    def "applyStatus handles null status gracefully without throwing"() {
        when: "applyStatus is called with null"
        driver.applyStatus(null)

        then: "no exception thrown"
        noExceptionThrown()
    }

    def "applyStatus handles empty result map without throwing"() {
        given: "a status with an empty result"
        def fixture = loadYamlFixture("LevoitGeneric.yaml")
        def status = [code: 0, result: [:], traceId: "test"]

        when:
        driver.applyStatus(status)

        then:
        noExceptionThrown()
        lastEventValue("compat") == "unknown shape"
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #12: pref-seed
    // -------------------------------------------------------------------------

    def "pref-seed fires when descriptionTextEnable is null and sets it to true (Bug Pattern #12)"() {
        given: "settings has descriptionTextEnable=null (first install before Save)"
        settings.descriptionTextEnable = null
        assert !state.prefsSeeded

        when: "applyStatus is called (pref-seed is at the top of applyStatus)"
        def fixture = loadYamlFixture("LevoitGeneric.yaml")
        driver.applyStatus(purifierStatusEnvelope(fixture.responses.purifier_on_manual as Map))

        then: "updateSetting was called to seed descriptionTextEnable=true"
        def seedCall = testDevice.settingsUpdates.find { it.name == "descriptionTextEnable" }
        seedCall != null
        seedCall.value == true
        state.prefsSeeded == true
    }

    def "pref-seed does NOT overwrite descriptionTextEnable when user has set it to false (Bug Pattern #12)"() {
        given: "user has explicitly set descriptionTextEnable=false"
        settings.descriptionTextEnable = false
        assert !state.prefsSeeded

        when:
        def fixture = loadYamlFixture("LevoitGeneric.yaml")
        driver.applyStatus(purifierStatusEnvelope(fixture.responses.purifier_on_manual as Map))

        then: "updateSetting NOT called for descriptionTextEnable (user setting preserved)"
        def seedCall = testDevice.settingsUpdates.find { it.name == "descriptionTextEnable" }
        seedCall == null
        state.prefsSeeded == true
    }

    def "pref-seed only fires once across multiple applyStatus calls (Bug Pattern #12)"() {
        given: "settings has descriptionTextEnable=null"
        settings.descriptionTextEnable = null
        def fixture = loadYamlFixture("LevoitGeneric.yaml")
        def status = purifierStatusEnvelope(fixture.responses.purifier_on_manual as Map)

        when: "applyStatus is called twice"
        driver.applyStatus(status)
        driver.applyStatus(status)

        then: "updateSetting for descriptionTextEnable was called exactly once"
        def seedCalls = testDevice.settingsUpdates.findAll { it.name == "descriptionTextEnable" }
        seedCalls.size() == 1
    }

    // -------------------------------------------------------------------------
    // Happy path: purifier-shape response
    // -------------------------------------------------------------------------

    def "applyStatus happy path from purifier-shape fixture emits expected events"() {
        given:
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("LevoitGeneric.yaml")
        def status = purifierStatusEnvelope(fixture.responses.purifier_on_manual as Map)

        when:
        driver.applyStatus(status)

        then: "switch is on"
        lastEventValue("switch") == "on"

        and: "compat is 'v2-api purifier (N fields)'"
        String compat = lastEventValue("compat") as String
        compat.startsWith("v2-api purifier")

        and: "airQualityIndex is the canonical Hubitat AirQuality capability attribute (numeric)"
        lastEventValue("airQualityIndex") == 1

        and: "airQuality is the categorical mapping from AQLevel (matches Vital 200S precedent)"
        lastEventValue("airQuality") == "good"

        and: "filter is populated from filterLifePercent"
        lastEventValue("filter") == 75

        and: "mode is populated from workMode"
        lastEventValue("mode") == "manual"

        and: "info attribute is populated"
        lastEventValue("info") != null

        and: "no errors logged"
        testLog.errors.isEmpty()
    }

    def "applyStatus emits switch=off when powerSwitch=0 in purifier shape"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LevoitGeneric.yaml")
        def status = purifierStatusEnvelope(fixture.responses.purifier_off as Map)

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("switch") == "off"
    }

    // -------------------------------------------------------------------------
    // Happy path: humidifier-shape response
    // -------------------------------------------------------------------------

    def "applyStatus happy path from humidifier-shape fixture emits expected events"() {
        given:
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("LevoitGeneric.yaml")
        // Use double-wrap (humidifier shape) to also cover BP#3 in happy path
        def status = humidifierStatusEnvelope(fixture.responses.humidifier_on_auto as Map)

        when:
        driver.applyStatus(status)

        then: "switch is on"
        lastEventValue("switch") == "on"

        and: "compat is 'v2-api humidifier (N fields)'"
        String compat = lastEventValue("compat") as String
        compat.startsWith("v2-api humidifier")

        and: "humidity is populated"
        lastEventValue("humidity") == 42

        and: "mode is 'auto' (reverse-mapped from autoPro)"
        lastEventValue("mode") == "auto"

        and: "level is populated (mapped from virtualLevel)"
        lastEventValue("level") != null

        and: "filter is populated from filterLifePercent"
        lastEventValue("filter") == 90

        and: "no errors logged"
        testLog.errors.isEmpty()
    }

    def "autoPro workMode is reverse-mapped to 'auto' in humidifier-shape response"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LevoitGeneric.yaml")
        def deviceData = fixture.responses.humidifier_on_auto as Map
        assert deviceData.workMode == "autoPro"
        def status = humidifierStatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "mode attribute is 'auto', not 'autoPro'"
        lastEventValue("mode") == "auto"
        lastEventValue("mode") != "autoPro"
    }

    // -------------------------------------------------------------------------
    // Unknown shape handling
    // -------------------------------------------------------------------------

    def "applyStatus sets compat to 'unknown shape' when response has minimal fields"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LevoitGeneric.yaml")
        def status = purifierStatusEnvelope(fixture.responses.unknown_shape_minimal as Map)

        when:
        driver.applyStatus(status)

        then: "compat is 'unknown shape'"
        lastEventValue("compat") == "unknown shape"

        and: "switch is still emitted (powerSwitch field was present)"
        lastEventValue("switch") == "on"

        and: "no exception thrown"
        noExceptionThrown()
    }

    def "compat is 'v2-api purifier' when multiple purifier-indicator fields are present"() {
        given: "a status with typical purifier fields"
        settings.descriptionTextEnable = false
        def deviceData = [
            powerSwitch: 1,
            PM25: 10,
            AQLevel: 2,
            fanSpeedLevel: 2,
            manualSpeedLevel: 2,
            filterLifePercent: 80
        ]
        def status = purifierStatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then:
        String compat = lastEventValue("compat") as String
        compat.startsWith("v2-api purifier")
    }

    def "compat is 'v2-api humidifier' when multiple humidifier-indicator fields are present"() {
        given: "a status with typical humidifier fields"
        settings.descriptionTextEnable = false
        def deviceData = [
            powerSwitch: 1,
            humidity: 50,
            mistLevel: 3,
            virtualLevel: 3,
            targetHumidity: 60,
            waterLacksState: 0
        ]
        def status = purifierStatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then:
        String compat = lastEventValue("compat") as String
        compat.startsWith("v2-api humidifier")
    }

    // -------------------------------------------------------------------------
    // captureDiagnostics produces well-formed markdown
    // -------------------------------------------------------------------------

    def "captureDiagnostics() command produces well-formed markdown in diagnostics attribute"() {
        given: "a device with known data values"
        settings.descriptionTextEnable = false
        // Use the public updateDataValue API (dataValues field is private)
        testDevice.updateDataValue("deviceType", "LAP-UNKNOWN-WUS")
        testDevice.updateDataValue("cid", "test-cid-12345678")

        when: "captureDiagnostics is called"
        driver.captureDiagnostics()

        then: "diagnostics attribute was emitted"
        def diagEvent = testDevice.events.find { it.name == "diagnostics" }
        diagEvent != null

        and: "value contains the markdown header"
        String diag = diagEvent.value as String
        diag.contains("Levoit Generic Device Diagnostic Capture")

        and: "value contains getPurifierStatus section"
        diag.contains("getPurifierStatus")

        and: "value contains getHumidifierStatus section"
        diag.contains("getHumidifierStatus")

        and: "value contains Interpretation section"
        diag.contains("Interpretation")

        and: "value ends with link hint for the issue template"
        diag.contains("new-device-support")

        and: "no exceptions thrown"
        noExceptionThrown()
    }

    def "captureDiagnostics() sends both getPurifierStatus and getHumidifierStatus API calls"() {
        given:
        settings.descriptionTextEnable = false
        testDevice.updateDataValue("deviceType", "LAP-UNKNOWN-WUS")

        when:
        driver.captureDiagnostics()

        then: "both probe methods were requested"
        def purifierReq = testParent.allRequests.find { it.method == "getPurifierStatus" }
        def humidifierReq = testParent.allRequests.find { it.method == "getHumidifierStatus" }
        purifierReq != null
        humidifierReq != null
    }

    // -------------------------------------------------------------------------
    // Power control
    // -------------------------------------------------------------------------

    def "on() sends setSwitch with powerSwitch=1 and switchIdx=0"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.on()

        then: "setSwitch was sent with correct payload"
        def req = testParent.allRequests.find { it.method == "setSwitch" }
        req != null
        req.data.powerSwitch == 1
        req.data.switchIdx == 0
    }

    def "off() sends setSwitch with powerSwitch=0 and switchIdx=0"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.off()

        then: "setSwitch was sent with correct payload"
        def req = testParent.allRequests.find { it.method == "setSwitch" }
        req != null
        req.data.powerSwitch == 0
        req.data.switchIdx == 0
    }

    def "on() falls back to setPower when setSwitch returns inner code -1"() {
        given: "TestParent is configured to return innerCode=-1 for the first call (setSwitch)"
        settings.descriptionTextEnable = false
        // Pre-program the first sendBypassRequest call to return inner code -1 (device rejection)
        testParent.cannedResponse = TestParent.innerErrorResponse()

        when:
        driver.on()

        then: "setPower was also attempted as V1 fallback (after setSwitch failed)"
        def setPowerReq = testParent.allRequests.find { it.method == "setPower" }
        setPowerReq != null
        setPowerReq.data.powerSwitch == 1
    }
}
