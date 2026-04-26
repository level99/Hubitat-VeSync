package drivers

import support.HubitatSpec
import support.TestDevice

/**
 * Unit tests for VeSyncIntegration.groovy (parent driver).
 *
 * Covers:
 *   Bug Pattern #2  — updateDevices() branches on device type name:
 *                     "Humidifier" in typeName -> getHumidifierStatus
 *                     otherwise              -> getPurifierStatus
 *   Bug Pattern #12 — pref-seed at top of updateDevices()
 *   sanitize()      — PII redaction: email, accountID, token, password all redacted
 *   deviceType()    — switch recognizes V201S regional variants
 *
 * ARCHITECTURE NOTE: The parent driver is NOT a child — it runs differently.
 * It calls httpPost (for login/getDevices), not parent.sendBypassRequest.
 * For testing its pure helper methods, we load it as a script and call
 * private/package-accessible methods via Groovy reflection or by invoking
 * them through their public interfaces.
 *
 * We CANNOT easily test the full updateDevices() → httpPost flow here because
 * updateDevices() makes real HTTP calls via httpPost. Instead we:
 *   1. Test deviceType() by calling it directly.
 *   2. Test sanitize() by calling it via logInfo/logDebug which route through it.
 *   3. Test pref-seed in updateDevices() by prepping state and calling it directly,
 *      with httpPost wired to a no-op (it will short-circuit when state.driverReloading is set).
 *
 * For the updateDevices() branching test, we use a targeted approach:
 *   - Wire two mock child devices (one humidifier, one purifier) into state.deviceList
 *   - Wire httpPost (called by sendBypassRequest inside updateDevices) to capture method
 *   - Verify the payload.method differs per device type
 *
 * IMPORTANT: The parent driver calls sendBypassRequest (not parent.sendBypassRequest).
 * Its httpPost is a top-level sandbox global. We wire it via metaClass in HubitatSpec.
 * For the parent driver spec, we OVERRIDE the httpPost mock to capture calls instead
 * of throwing.
 */
// NOTE: deviceType() tests rely on Groovy 3 dynamic dispatch ignoring 'private'.
// Do NOT add @CompileStatic to VeSyncIntegration.groovy or these 12 tests will break.
class VeSyncIntegrationSpec extends HubitatSpec {

    // Captured HTTP POST calls for verifying updateDevices() branching
    List<Map> capturedHttpPosts = []
    List<Map> capturedBypassBodies = []

    @Override
    String driverSourcePath() {
        "Drivers/Levoit/VeSyncIntegration.groovy"
    }

    @Override
    Map defaultSettings() {
        return [
            descriptionTextEnable: true,
            debugOutput: false,
            verboseDebug: false,
            refreshInterval: 30,
            email: "test@example.com",
            password: "testpassword"
        ]
    }

    /**
     * Override sandbox wiring to customize httpPost for parent driver tests.
     * The parent uses httpPost directly (not via parent.sendBypassRequest),
     * so we need to capture calls without throwing.
     */
    @Override
    protected void wireSandbox(def driverInstance) {
        super.wireSandbox(driverInstance)
        def mc = driverInstance.metaClass
        def self = this

        // Override httpPost to capture the request body (for bypass method verification)
        // and call the closure with a minimal success response.
        mc.httpPost = { Map params, Closure callback ->
            self.capturedHttpPosts << params.clone()
            // Extract the bypass body if present
            if (params.body?.payload) {
                self.capturedBypassBodies << (params.body.clone() as Map)
            }
            // Simulate a minimal success response so the driver's closure body runs
            def fakeResp = new Expando()
            fakeResp.status = 200
            fakeResp.data = [
                code: 0,
                result: [
                    code: 0,
                    result: [:],
                    traceId: "test-trace"
                ],
                traceId: "test-trace"
            ]
            callback(fakeResp)
        }

        // getChildDevice: used by updateDevices() to find the child driver object
        // Return a mock child device keyed by DNI from state.deviceList
        mc.getChildDevice = { String dni ->
            self.childDevices[dni]
        }

        // getChildDevices: used by getDevices() cleanup pass
        mc.getChildDevices = { -> [] }

        // addChildDevice: no-op in these tests
        mc.addChildDevice = { String typeName, String dni, Map props ->
            def dev = new TestDevice()
            dev.name = typeName
            self.childDevices[dni] = dev
            dev
        }

        // deleteChildDevice: no-op
        mc.deleteChildDevice = { String dni -> }

        // sendEvent: parent fires heartbeat events
        mc.sendEvent = { Map args -> testDevice.sendEvent(args) }
    }

    // Child device registry for parent test — keyed by DNI
    Map<String, TestDevice> childDevices = [:]

    def setup() {
        capturedHttpPosts = []
        capturedBypassBodies = []
        childDevices = [:]
    }

    // -------------------------------------------------------------------------
    // sanitize() PII redaction
    // -------------------------------------------------------------------------

    def "sanitize() redacts email address from log messages"() {
        given: "driver has a settings.email to redact"
        settings.email = "user@example.com"
        settings.descriptionTextEnable = true

        when: "logInfo is called with a message containing the email"
        // We call logInfo which routes through sanitize().
        // The redacted message goes to testLog.infos.
        driver.logInfo("Login successful for user@example.com")

        then: "log message has email redacted"
        testLog.infos.any { it.contains("[email-redacted]") }
        !testLog.infos.any { it.contains("user@example.com") }
    }

    def "sanitize() redacts state.accountID from log messages"() {
        given:
        settings.descriptionTextEnable = true
        state.accountID = "ACC12345"

        when:
        driver.logInfo("AccountID is ACC12345 and token is XYZ")

        then:
        testLog.infos.any { it.contains("[accountID-redacted]") }
        !testLog.infos.any { it.contains("ACC12345") }
    }

    def "sanitize() redacts state.token from log messages"() {
        given:
        settings.descriptionTextEnable = true
        state.token = "mySecretToken999"

        when:
        driver.logInfo("Token: mySecretToken999")

        then:
        testLog.infos.any { it.contains("[token-redacted]") }
        !testLog.infos.any { it.contains("mySecretToken999") }
    }

    def "sanitize() redacts settings.password from log messages"() {
        given:
        settings.descriptionTextEnable = true
        settings.password = "hunter2"

        when:
        driver.logError("Auth failed, password was hunter2")

        then:
        testLog.errors.any { it.contains("[password-redacted]") }
        !testLog.errors.any { it.contains("hunter2") }
    }

    def "sanitize() handles null message gracefully"() {
        when:
        driver.logInfo(null)

        then:
        noExceptionThrown()
    }

    def "sanitize() does not corrupt message when no PII present"() {
        given:
        settings.descriptionTextEnable = true

        when:
        driver.logInfo("Login successful, 3 devices discovered")

        then:
        testLog.infos.any { it.contains("Login successful") && it.contains("3 devices discovered") }
    }

    // -------------------------------------------------------------------------
    // deviceType() — regional variant recognition
    // -------------------------------------------------------------------------

    def "deviceType() recognizes LAP-V201S-WUS as V200S"() {
        expect:
        driver.deviceType("LAP-V201S-WUS") == "V200S"
    }

    def "deviceType() recognizes LAP-V201S-WUSR as V200S"() {
        expect:
        driver.deviceType("LAP-V201S-WUSR") == "V200S"
    }

    def "deviceType() recognizes LAP-V201S-AASR as V200S"() {
        expect:
        driver.deviceType("LAP-V201S-AASR") == "V200S"
    }

    def "deviceType() recognizes LAP-V201S-WEU as V200S"() {
        expect:
        driver.deviceType("LAP-V201S-WEU") == "V200S"
    }

    def "deviceType() recognizes LAP-V201S-AUSR as V200S (Australia variant from TODO)"() {
        expect:
        driver.deviceType("LAP-V201S-AUSR") == "V200S"
    }

    def "deviceType() recognizes Vital200S string as V200S"() {
        expect:
        driver.deviceType("Vital200S") == "V200S"
    }

    def "deviceType() recognizes Core200S as 200S"() {
        expect:
        driver.deviceType("Core200S") == "200S"
    }

    def "deviceType() recognizes Core400S as 400S"() {
        expect:
        driver.deviceType("Core400S") == "400S"
    }

    def "deviceType() recognizes LEH-S601S-WUS as V601S (Superior 6000S)"() {
        expect:
        driver.deviceType("LEH-S601S-WUS") == "V601S"
    }

    def "deviceType() recognizes LEH-S601S-WUSR as V601S"() {
        expect:
        driver.deviceType("LEH-S601S-WUSR") == "V601S"
    }

    def "deviceType() recognizes LEH-S602S-WUS as V601S (variant)"() {
        expect:
        driver.deviceType("LEH-S602S-WUS") == "V601S"
    }

    def "deviceType() returns N/A for unknown codes"() {
        expect:
        driver.deviceType("UnknownModel-XYZ") == "N/A"
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #2: updateDevices() branches by typeName
    // -------------------------------------------------------------------------
    // NOTE: VeSyncIntegration.groovy line 262 does `result = dev.update(...)` with no
    // `def result` declared. This is a pre-existing parent driver bug (out of scope for
    // round 2 — only Core 300/400/600 ordering and Core200S Light `return result` were
    // approved for driver modification). The closure-internal GroovyRuntimeException is
    // absorbed by the parent's try/catch, so the captured-payload assertions below remain
    // valid regardless: the bypass body is sent before update() is called on the child.
    // -------------------------------------------------------------------------

    def "updateDevices() uses getPurifierStatus for purifier device (Bug Pattern #2)"() {
        given: "state has a purifier device"
        settings.refreshInterval = 30
        settings.descriptionTextEnable = false
        state.prefsSeeded = true
        state.deviceList = ["PURIFIER-CID": "test-config-module"]

        // Wire a mock child device with purifier typeName
        def purifierDevice = new TestDevice()
        purifierDevice.typeName = "Levoit Vital 200S Air Purifier"
        // Mock its update(status, nightLight) method via metaClass
        purifierDevice.metaClass.update = { Map st, nl -> true }
        childDevices["PURIFIER-CID"] = purifierDevice

        when:
        driver.updateDevices()

        then: "the bypass request body used getPurifierStatus"
        def purifierBody = capturedBypassBodies.find { b ->
            b.payload?.method == "getPurifierStatus"
        }
        purifierBody != null
    }

    def "updateDevices() uses getHumidifierStatus for humidifier device (Bug Pattern #2)"() {
        given: "state has a humidifier device"
        settings.refreshInterval = 30
        settings.descriptionTextEnable = false
        state.prefsSeeded = true
        state.deviceList = ["HUMIDIFIER-CID": "test-config-module"]

        // Wire a mock child device with humidifier typeName
        def humDevice = new TestDevice()
        humDevice.typeName = "Levoit Superior 6000S Humidifier"
        humDevice.metaClass.update = { Map st, nl -> true }
        childDevices["HUMIDIFIER-CID"] = humDevice

        when:
        driver.updateDevices()

        then: "the bypass request body used getHumidifierStatus"
        def humBody = capturedBypassBodies.find { b ->
            b.payload?.method == "getHumidifierStatus"
        }
        humBody != null
    }

    def "updateDevices() correctly branches both devices in same deviceList (Bug Pattern #2)"() {
        given: "state has both a purifier and a humidifier"
        settings.refreshInterval = 30
        settings.descriptionTextEnable = false
        state.prefsSeeded = true
        state.deviceList = [
            "PURIFIER-CID":   "config-module-p",
            "HUMIDIFIER-CID": "config-module-h"
        ]

        def purifierDevice = new TestDevice()
        purifierDevice.typeName = "Levoit Core 400S Air Purifier"
        purifierDevice.metaClass.update = { Map st, nl -> true }
        childDevices["PURIFIER-CID"] = purifierDevice

        def humDevice = new TestDevice()
        humDevice.typeName = "Levoit Superior 6000S Humidifier"
        humDevice.metaClass.update = { Map st, nl -> true }
        childDevices["HUMIDIFIER-CID"] = humDevice

        when:
        driver.updateDevices()

        then: "getPurifierStatus was sent for the purifier"
        capturedBypassBodies.any { it.payload?.method == "getPurifierStatus" }

        and: "getHumidifierStatus was sent for the humidifier"
        capturedBypassBodies.any { it.payload?.method == "getHumidifierStatus" }
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #12: pref-seed in updateDevices()
    // -------------------------------------------------------------------------

    def "pref-seed fires at top of updateDevices() when descriptionTextEnable is null (Bug Pattern #12)"() {
        given:
        settings.descriptionTextEnable = null
        settings.refreshInterval = 30
        state.deviceList = [:]   // empty — no HTTP calls to sendBypassRequest
        assert !state.prefsSeeded

        when:
        driver.updateDevices()

        then: "pref-seed ran"
        state.prefsSeeded == true
        def seedCall = testDevice.settingsUpdates.find { it.name == "descriptionTextEnable" }
        seedCall != null
        seedCall.value == true
    }
}
