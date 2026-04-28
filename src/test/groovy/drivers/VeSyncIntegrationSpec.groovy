package drivers

import spock.lang.Unroll
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
 *   Bug Pattern #13 — getDevices() re-auth on HTTP 401 / inner auth codes (Issue 3)
 *   sanitize()      — PII redaction: email, accountID, token, password all redacted
 *   deviceType()    — switch recognizes V201S regional variants
 *   Issue 2         — updateDevices() no longer throws MissingPropertyException
 *                     (removed unused `result =` assignment)
 *   Pattern 2       — sendBypassRequest body.deviceRegion reads from getDeviceRegion()
 *                     (preference-backed, Elvis fallback "US" for backwards compat; v2.2)
 *   Section I       — Bug Pattern #14 (schedule()-based poll-cycle recovery + cron-syntax fix):
 *                     setupPollSchedule() seconds-cron (30s, 59s, null-default),
 *                     minutes-cron (60s, 120s, 300s boundaries), non-multiple WARN (90s),
 *                     ensurePollWatchdog() migration + idempotency,
 *                     updateDevices() + sendBypassRequest() integration paths
 *   Section J       — EU region support (v2.2 preview):
 *                     getDeviceRegion() default, EU setting, getApiHost() routing,
 *                     updated() region-change clears token, no-op on same-region save,
 *                     bypassV2 body reflects preference value
 *   Section K       — Model code routing v2.2 audit additions (6 new codes):
 *                     LUH-O451S-WEU, LAP-V201S-AEUR, LAP-V201-AUSR (no-S typo SKU),
 *                     LAP-C202S-WUSR, LAP-V201S-WJP, LAP-C302S-WUSB
 *   Section L       — Bug Pattern #16 (debug auto-disable watchdog, parent):
 *                     updated() timestamp set/clear, ensureDebugWatchdog() no-op below
 *                     threshold, fires above threshold, called from updateDevices()
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

    def "deviceType() returns GENERIC for unknown codes (fall-through to LevoitGeneric driver)"() {
        // Unknown model codes route to the Generic Levoit Diagnostic Driver per the v2.0+
        // fall-through behavior. Was "N/A" pre-Generic; now "GENERIC" so unknown devices
        // get a working Hubitat presence + captureDiagnostics() self-service instead of
        // silent skip. See LevoitGeneric.groovy + the new-device-support issue template.
        expect:
        driver.deviceType("UnknownModel-XYZ") == "GENERIC"
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #2: updateDevices() branches by typeName
    // Issue 2: `result = dev.update(...)` was dropped — no longer throws
    //          MissingPropertyException on every poll cycle.
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

    // -------------------------------------------------------------------------
    // Bug Pattern #13: token-expiry re-auth in sendBypassRequest()
    //
    // Architecture note:
    //   sendBypassRequest() is a method on the parent driver itself. It calls
    //   httpPost() and login() as bare top-level calls (sandbox globals / driver
    //   methods). We override both via the driver's metaClass inside each test's
    //   given: block to inject controlled responses.
    //
    //   login() is a private method on the parent driver. Groovy 3 ignores
    //   private on dynamic dispatch, so we can override it via metaClass the
    //   same way we override httpPost.
    //
    //   Equipment mock: we use a TestDevice as the 'equipment' arg (it provides
    //   getDataValue("cid") and getDataValue("configModule") via the TestDevice
    //   default values).
    //
    //   Closure tracking: sendBypassRequest takes a caller-provided Closure.
    //   We capture what response that closure received by storing it in a local
    //   list that the closure writes to.
    // -------------------------------------------------------------------------

    /**
     * Helper: build a minimal Expando response for httpPost callback.
     * status + data.code shape mirrors what Hubitat's httpPost callback receives.
     */
    private Expando makeResponse(int httpStatus, Integer outerCode, Map resultData = [:]) {
        def r = new Expando()
        r.status = httpStatus
        r.data = [
            code: outerCode,
            result: [code: 0, result: resultData, traceId: "test-trace"],
            traceId: "test-trace"
        ]
        return r
    }

    /** Auth-failure response: HTTP 200, outer code -11001000 (TOKEN_EXPIRED). */
    private Expando authFailure_11001000() {
        def r = new Expando()
        r.status = 200
        r.data = [code: -11001000, result: [:], traceId: "test-trace"]
        return r
    }

    /** Auth-failure response: HTTP 200, outer code -11201000 (PASSWORD_ERROR). */
    private Expando authFailure_11201000() {
        def r = new Expando()
        r.status = 200
        r.data = [code: -11201000, result: [:], traceId: "test-trace"]
        return r
    }

    /** Auth-failure response: HTTP 401 (transport-level). */
    private Expando authFailure_401() {
        def r = new Expando()
        r.status = 401
        r.data = null
        return r
    }

    /** Normal success response used as the retry response. */
    private Expando successResp(Map deviceData = [powerSwitch: 1]) {
        def r = new Expando()
        r.status = 200
        r.data = [
            code: 0,
            result: [code: 0, result: deviceData, traceId: "test-trace"],
            traceId: "test-trace"
        ]
        return r
    }

    def "sendBypassRequest detects HTTP 401 and re-authenticates (Bug Pattern #13)"() {
        given: "httpPost returns 401 on first call, then success on retry"
        settings.descriptionTextEnable = true
        settings.debugOutput = false
        state.token     = "old-token"
        state.accountID = "acc-001"
        int httpPostCallCount = 0
        List<Object> closureReceivedResponses = []
        def equip = new TestDevice()

        // Wire login() to succeed and update state token
        driver.metaClass.login = { ->
            state.token     = "new-token"
            state.accountID = "acc-001"
            true
        }

        // Wire httpPost: first call returns 401, second call returns success
        driver.metaClass.httpPost = { Map params, Closure callback ->
            httpPostCallCount++
            if (httpPostCallCount == 1) {
                callback(authFailure_401())
            } else {
                callback(successResp())
            }
        }

        // The caller's closure records what it receives
        Closure callerClosure = { resp -> closureReceivedResponses << resp }

        when:
        driver.sendBypassRequest(equip, [method: "getPurifierStatus", source: "APP", data: [:]], callerClosure)

        then: "login() was called once"
        // We can't count login calls directly, but we can verify the token was refreshed
        state.token == "new-token"

        and: "httpPost was called twice (initial + retry)"
        httpPostCallCount == 2

        and: "the caller's closure received the retry's success response (not the 401)"
        closureReceivedResponses.size() == 1
        closureReceivedResponses[0].status == 200
        closureReceivedResponses[0].data?.code == 0

        and: "an info log about re-authentication was emitted"
        testLog.infos.any { it.contains("re-authenticating") }

        and: "state.reAuthInProgress is cleared"
        !state.containsKey('reAuthInProgress')
    }

    def "sendBypassRequest detects inner code -11001000 and re-authenticates (Bug Pattern #13)"() {
        given: "httpPost returns HTTP 200 with inner code -11001000 on first call"
        settings.descriptionTextEnable = true
        settings.debugOutput = false
        state.token     = "old-token"
        state.accountID = "acc-001"
        int httpPostCallCount = 0
        List<Object> closureReceivedResponses = []
        def equip = new TestDevice()

        driver.metaClass.login = { ->
            state.token = "new-token"
            true
        }
        driver.metaClass.httpPost = { Map params, Closure callback ->
            httpPostCallCount++
            if (httpPostCallCount == 1) {
                callback(authFailure_11001000())
            } else {
                callback(successResp())
            }
        }
        Closure callerClosure = { resp -> closureReceivedResponses << resp }

        when:
        driver.sendBypassRequest(equip, [method: "getHumidifierStatus", source: "APP", data: [:]], callerClosure)

        then: "re-auth was triggered"
        state.token == "new-token"

        and: "httpPost was called twice"
        httpPostCallCount == 2

        and: "caller's closure received the retry's success response"
        closureReceivedResponses.size() == 1
        closureReceivedResponses[0].status == 200
        closureReceivedResponses[0].data?.code == 0

        and: "state.reAuthInProgress is cleared"
        !state.containsKey('reAuthInProgress')
    }

    def "sendBypassRequest detects inner code -11201000 and re-authenticates (Bug Pattern #13)"() {
        given: "httpPost returns HTTP 200 with inner code -11201000 (PASSWORD_ERROR) on first call"
        settings.descriptionTextEnable = true
        settings.debugOutput = false
        state.token     = "old-token"
        state.accountID = "acc-001"
        int httpPostCallCount = 0
        List<Object> closureReceivedResponses = []
        def equip = new TestDevice()

        driver.metaClass.login = { ->
            state.token = "refreshed-token"
            true
        }
        driver.metaClass.httpPost = { Map params, Closure callback ->
            httpPostCallCount++
            if (httpPostCallCount == 1) {
                callback(authFailure_11201000())
            } else {
                callback(successResp())
            }
        }
        Closure callerClosure = { resp -> closureReceivedResponses << resp }

        when:
        driver.sendBypassRequest(equip, [method: "setSwitch", source: "APP", data: [powerSwitch: 1, switchIdx: 0]], callerClosure)

        then: "re-auth triggered and token refreshed"
        state.token == "refreshed-token"

        and: "httpPost called twice"
        httpPostCallCount == 2

        and: "caller's closure received retry success"
        closureReceivedResponses.size() == 1
        closureReceivedResponses[0].data?.code == 0

        and: "state.reAuthInProgress is cleared"
        !state.containsKey('reAuthInProgress')
    }

    def "sendBypassRequest does NOT re-authenticate on inner code -1 -- not an auth failure (Bug Pattern #13 negative control)"() {
        given: "httpPost returns inner code -1 (device-side rejection, e.g. wrong method on humidifier)"
        settings.descriptionTextEnable = true
        settings.debugOutput = false
        state.token     = "my-token"
        state.accountID = "acc-001"
        int httpPostCallCount = 0
        int loginCallCount    = 0
        List<Object> closureReceivedResponses = []
        def equip = new TestDevice()

        driver.metaClass.login = { ->
            loginCallCount++
            true
        }
        driver.metaClass.httpPost = { Map params, Closure callback ->
            httpPostCallCount++
            // Inner code -1: device rejected the method (not an auth failure)
            def r = new Expando()
            r.status = 200
            r.data = [code: -1, result: [:], traceId: "test-trace"]
            callback(r)
        }
        Closure callerClosure = { resp -> closureReceivedResponses << resp }

        when:
        driver.sendBypassRequest(equip, [method: "getPurifierStatus", source: "APP", data: [:]], callerClosure)

        then: "login() was NOT called"
        loginCallCount == 0

        and: "httpPost was called exactly once (no retry)"
        httpPostCallCount == 1

        and: "caller's closure received the -1 response directly"
        closureReceivedResponses.size() == 1
        closureReceivedResponses[0].data?.code == -1

        and: "no info log about re-authentication"
        !testLog.infos.any { it.contains("re-authenticating") }
    }

    def "sendBypassRequest does NOT recurse when login fails (Bug Pattern #13)"() {
        given: "login() returns false; caller's closure should receive original auth-failure response"
        settings.descriptionTextEnable = true
        settings.debugOutput = false
        state.token     = "expired-token"
        state.accountID = "acc-001"
        int httpPostCallCount = 0
        int loginCallCount    = 0
        List<Object> closureReceivedResponses = []
        def equip = new TestDevice()

        driver.metaClass.login = { ->
            loginCallCount++
            false  // login fails
        }
        driver.metaClass.httpPost = { Map params, Closure callback ->
            httpPostCallCount++
            callback(authFailure_11001000())
        }
        Closure callerClosure = { resp -> closureReceivedResponses << resp }

        when:
        driver.sendBypassRequest(equip, [method: "getPurifierStatus", source: "APP", data: [:]], callerClosure)

        then: "login() was attempted exactly once"
        loginCallCount == 1

        and: "httpPost was called exactly once (no retry after failed login)"
        httpPostCallCount == 1

        and: "caller's closure received the original auth-failure response"
        closureReceivedResponses.size() == 1
        closureReceivedResponses[0].data?.code == -11001000

        and: "an error log about re-auth failure was emitted"
        testLog.errors.any { it.contains("Re-auth failed") }

        and: "state.reAuthInProgress is cleared after login failure"
        !state.containsKey('reAuthInProgress')
    }

    def "state.reAuthInProgress is cleared after successful re-auth (Bug Pattern #13)"() {
        given: "normal re-auth flow"
        settings.descriptionTextEnable = true
        settings.debugOutput = false
        state.token     = "old-token"
        state.accountID = "acc-001"
        def equip = new TestDevice()
        Boolean reAuthInProgressDuringRetry = null  // captured mid-retry

        driver.metaClass.login = { ->
            state.token = "fresh-token"
            true
        }
        driver.metaClass.httpPost = { Map params, Closure callback ->
            if (state.reAuthInProgress == null || !state.reAuthInProgress) {
                // First call (no guard yet, or guard cleared): trigger auth failure
                callback(authFailure_11001000())
            } else {
                // Second call happens while reAuthInProgress == true (guard is set inside the closure)
                reAuthInProgressDuringRetry = state.reAuthInProgress
                callback(successResp())
            }
        }
        Closure callerClosure = { resp -> /* consume */ }

        when:
        driver.sendBypassRequest(equip, [method: "getPurifierStatus", source: "APP", data: [:]], callerClosure)

        then: "guard was set during the retry (confirms finally block timing)"
        // reAuthInProgress was true when the retry httpPost was executing
        reAuthInProgressDuringRetry == true

        and: "guard is cleared after sendBypassRequest returns"
        !state.containsKey('reAuthInProgress')
    }

    def "state.reAuthInProgress is cleared after login failure (Bug Pattern #13 finally semantics)"() {
        given:
        settings.descriptionTextEnable = true
        settings.debugOutput = false
        state.token     = "expired-token"
        state.accountID = "acc-001"
        def equip = new TestDevice()

        driver.metaClass.login = { ->
            // Simulate login throwing an unexpected exception
            throw new RuntimeException("Network unreachable")
        }
        driver.metaClass.httpPost = { Map params, Closure callback ->
            callback(authFailure_11001000())
        }
        Closure callerClosure = { resp -> /* consume */ }

        when: "login() throws — the finally block must still clear the flag"
        // The outer try/catch in sendBypassRequest wraps everything, so
        // an exception from login() inside the closure will bubble up.
        // We expect the finally in the effectiveClosure to clear the flag
        // even when login() throws.
        try {
            driver.sendBypassRequest(equip, [method: "getPurifierStatus", source: "APP", data: [:]], callerClosure)
        } catch (ignored) { /* exception expected when login throws */ }

        then: "state.reAuthInProgress is cleared despite login() throwing"
        !state.containsKey('reAuthInProgress')
    }

    def "sendBypassRequest re-auth: retry request uses refreshed token in params (Bug Pattern #13)"() {
        given: "verify that params.body.token and params.headers.tk are updated before retry"
        settings.descriptionTextEnable = true
        settings.debugOutput = false
        state.token     = "token-v1"
        state.accountID = "acc-001"
        def equip = new TestDevice()
        String tokenUsedOnRetry = null
        String tkHeaderOnRetry  = null
        int callCount = 0

        driver.metaClass.login = { ->
            state.token     = "token-v2"
            state.accountID = "acc-001"
            true
        }
        driver.metaClass.httpPost = { Map params, Closure callback ->
            callCount++
            if (callCount == 1) {
                callback(authFailure_11001000())
            } else {
                // Capture what token was in the params on the retry call
                tokenUsedOnRetry = params.body?.token
                tkHeaderOnRetry  = params.headers?.tk
                callback(successResp())
            }
        }
        Closure callerClosure = { resp -> /* consume */ }

        when:
        driver.sendBypassRequest(equip, [method: "getPurifierStatus", source: "APP", data: [:]], callerClosure)

        then: "the retry used the refreshed token in both body and header"
        tokenUsedOnRetry == "token-v2"
        tkHeaderOnRetry  == "token-v2"
    }

    // -------------------------------------------------------------------------
    // getDevices() Bug Pattern #13 extension — auth-failure retry (Issue 3)
    //
    // Architecture note:
    //   getDevices() is private but accessible via Groovy 3 dynamic dispatch.
    //   It is wrapped in retryableHttp("getDevices", 3) which simply invokes
    //   the closure — the wrapper does not complicate these auth-retry tests.
    //
    //   The success response must include resp.data.result.list or getDevices()
    //   will short-circuit with a "No list in response result" error log. We
    //   provide a minimal devices-list response (empty list) for the retry path.
    //
    //   state.accountID / state.token must be set so getDevices() can build
    //   its request body before calling httpPost. If either is null, the body
    //   will contain null values — not an error for these auth-retry specs.
    // -------------------------------------------------------------------------

    /** Minimal success response for getDevices() — includes the required result.list field. */
    private Expando getDevicesSuccess() {
        def r = new Expando()
        r.status = 200
        r.data = [
            code: 0,
            result: [
                code: 0,
                list: [],
                total: 0
            ],
            traceId: "test-trace"
        ]
        return r
    }

    def "getDevices() re-authenticates on HTTP 401 and retries (Issue 3 / Bug Pattern #13)"() {
        // A1: golden retry path — HTTP 401 triggers re-auth, retry succeeds
        given: "httpPost returns 401 on first call, then a valid devices-list response on second call"
        settings.descriptionTextEnable = true
        settings.debugOutput = false
        settings.refreshInterval = 30  // success path calls runIn(5 * (int)settings.refreshInterval, ...) — must be non-null
        state.token     = "old-token"
        state.accountID = "acc-001"
        state.prefsSeeded = true
        int httpPostCallCount = 0
        int loginCallCount    = 0

        driver.metaClass.login = { ->
            loginCallCount++
            state.token     = "new-token"
            state.accountID = "acc-001"
            true
        }
        driver.metaClass.httpPost = { Map params, Closure callback ->
            httpPostCallCount++
            if (httpPostCallCount == 1) {
                callback(authFailure_401())
            } else {
                callback(getDevicesSuccess())
            }
        }

        when:
        def result = driver.getDevices()

        then: "login() was invoked exactly once between the two httpPost calls"
        loginCallCount == 1

        and: "httpPost was called twice (initial + retry after re-auth)"
        httpPostCallCount == 2

        and: "the retry used the refreshed token in both body and header"
        // The driver mutates params.body.token and params.headers.tk before retry.
        // We verify the side-effect: state.token was updated by our login() mock.
        state.token == "new-token"

        and: "state.reAuthInProgress is cleared after successful retry"
        !state.containsKey('reAuthInProgress')

        and: "getDevices() returned true (retry succeeded)"
        result == true
    }

    def "getDevices() re-authenticates on inner code -11001000 and retries (Issue 3 / Bug Pattern #13)"() {
        // A2: inner-code auth failure — HTTP 200 with code -11001000 triggers re-auth + retry
        given: "httpPost returns HTTP 200 with inner code -11001000 (TOKEN_EXPIRED) on first call"
        settings.descriptionTextEnable = true
        settings.debugOutput = false
        settings.refreshInterval = 30  // success path calls runIn(5 * (int)settings.refreshInterval, ...) — must be non-null
        state.token     = "stale-token"
        state.accountID = "acc-002"
        state.prefsSeeded = true
        int httpPostCallCount = 0
        int loginCallCount    = 0

        driver.metaClass.login = { ->
            loginCallCount++
            state.token = "refreshed-token"
            true
        }
        driver.metaClass.httpPost = { Map params, Closure callback ->
            httpPostCallCount++
            if (httpPostCallCount == 1) {
                callback(authFailure_11001000())
            } else {
                callback(getDevicesSuccess())
            }
        }

        when:
        def result = driver.getDevices()

        then: "login() was invoked (isAuthFailure covers both HTTP-401 and inner -11001000)"
        loginCallCount == 1

        and: "httpPost was called twice"
        httpPostCallCount == 2

        and: "state.reAuthInProgress is cleared"
        !state.containsKey('reAuthInProgress')

        and: "getDevices() returned true"
        result == true
    }

    def "getDevices() does NOT retry when login() fails (Issue 3 / Bug Pattern #13)"() {
        // A3: re-auth failure — first call returns 401, login() returns false, no retry
        given: "login() returns false; no retry httpPost should occur"
        settings.descriptionTextEnable = true
        settings.debugOutput = false
        state.token     = "expired-token"
        state.accountID = "acc-003"
        state.prefsSeeded = true
        int httpPostCallCount = 0
        int loginCallCount    = 0

        driver.metaClass.login = { ->
            loginCallCount++
            false  // re-auth fails
        }
        driver.metaClass.httpPost = { Map params, Closure callback ->
            httpPostCallCount++
            callback(authFailure_401())
        }

        when:
        def result = driver.getDevices()

        then: "login() was attempted exactly once"
        loginCallCount == 1

        and: "httpPost was called exactly once (no retry after failed login)"
        httpPostCallCount == 1

        and: "state.reAuthInProgress is cleared (finally semantics preserved)"
        !state.containsKey('reAuthInProgress')

        and: "getDevices() returned false (retry never happened)"
        result == false

        and: "an error log was emitted about re-auth failure"
        testLog.errors.any { it.contains("Re-auth failed") }
    }

    // -------------------------------------------------------------------------
    // Pattern 2 — deviceRegion in sendBypassRequest body (v2.2: preference-backed)
    // Regional routing is binary (US/EU). getDeviceRegion() reads settings?.deviceRegion
    // with "US" as the Elvis-fallback for backwards compatibility on existing installs.
    // The @Field DEVICE_REGION constant was removed in v2.2 -- this spec validates the
    // new preference-backed path still supplies "US" when preference is absent.
    // -------------------------------------------------------------------------

    def "sendBypassRequest body carries deviceRegion 'US' when preference is unset (Pattern 2 -- backwards compat)"() {
        // B: deviceRegion preference absent → getDeviceRegion() returns "US" via Elvis fallback.
        // Verifies backwards-compatibility for existing installs that were upgraded without
        // explicitly setting the new preference.
        given: "deviceRegion preference is absent (simulates pre-v2.2 install)"
        settings.remove('deviceRegion')
        settings.descriptionTextEnable = false
        settings.debugOutput = false
        state.token     = "tok-b"
        state.accountID = "acc-b"
        def equip = new TestDevice()
        capturedBypassBodies.clear()

        when:
        driver.sendBypassRequest(equip, [method: "getPurifierStatus", source: "APP", data: [:]], { resp -> })

        then: "the request body carries deviceRegion 'US' (Elvis fallback -- no preference set)"
        capturedBypassBodies.size() == 1
        capturedBypassBodies[0].deviceRegion == "US"
    }

    // -------------------------------------------------------------------------
    // Pattern 2 — getLocationTimeZone() honours hub timezone in sendBypassRequest
    // -------------------------------------------------------------------------

    def "sendBypassRequest uses hub timezone from location when available (Pattern 2 / getLocationTimeZone)"() {
        // C1: hub timezone is honoured — mock location.timeZone.ID to "America/Chicago"
        // and verify body.timeZone and headers.tz both carry the hub's zone, not the default.
        // Null-location fallback is exercised in spec C2 below.
        given: "hub's location exposes timezone 'America/Chicago'"
        settings.descriptionTextEnable = false
        settings.debugOutput = false
        state.token     = "tok-tz"
        state.accountID = "acc-tz"
        def equip = new TestDevice()

        // Override getLocation to return an object whose timeZone.ID is "America/Chicago"
        def fakeTimeZone = new Expando()
        fakeTimeZone.ID = "America/Chicago"
        def fakeLocation = new Expando()
        fakeLocation.timeZone = fakeTimeZone
        driver.metaClass.getLocation = { -> fakeLocation }

        // Capture the raw httpPost params so we can inspect both body and headers
        List<Map> rawPosts = []
        driver.metaClass.httpPost = { Map params, Closure callback ->
            rawPosts << params
            if (params.body?.payload) {
                capturedBypassBodies << (params.body.clone() as Map)
            }
            def fakeResp = new Expando()
            fakeResp.status = 200
            fakeResp.data = [
                code: 0,
                result: [code: 0, result: [:], traceId: "test-trace"],
                traceId: "test-trace"
            ]
            callback(fakeResp)
        }

        when:
        driver.sendBypassRequest(equip, [method: "getPurifierStatus", source: "APP", data: [:]], { resp -> })

        then: "body.timeZone reflects the hub's configured timezone"
        rawPosts.size() == 1
        rawPosts[0].body?.timeZone == "America/Chicago"

        and: "headers.tz also reflects the hub's configured timezone"
        rawPosts[0].headers?.tz == "America/Chicago"
    }

    def "getLocationTimeZone() falls back to DEFAULT_TIME_ZONE when hub location is null (Pattern 2)"() {
        // C2: null-location fallback — getLocation() returns null → getLocationTimeZone()
        // must return DEFAULT_TIME_ZONE ("America/Los_Angeles").
        //
        // The parent driver is the only consumer of getLocation() in this file;
        // overriding it to return null does not cascade NPEs to child drivers.
        given: "hub location is null (no location configured)"
        settings.descriptionTextEnable = false
        settings.debugOutput = false
        state.token     = "tok-c2"
        state.accountID = "acc-c2"
        def equip = new TestDevice()

        // Override getLocation to simulate hub with no location configured
        driver.metaClass.getLocation = { -> null }

        // Capture raw httpPost params to inspect body and headers
        List<Map> rawPosts = []
        driver.metaClass.httpPost = { Map params, Closure callback ->
            rawPosts << params
            if (params.body?.payload) {
                capturedBypassBodies << (params.body.clone() as Map)
            }
            def fakeResp = new Expando()
            fakeResp.status = 200
            fakeResp.data = [
                code: 0,
                result: [code: 0, result: [:], traceId: "test-trace"],
                traceId: "test-trace"
            ]
            callback(fakeResp)
        }

        when:
        driver.sendBypassRequest(equip, [method: "getPurifierStatus", source: "APP", data: [:]], { resp -> })

        then: "body.timeZone falls back to the DEFAULT_TIME_ZONE constant"
        rawPosts.size() == 1
        rawPosts[0].body?.timeZone == "America/Los_Angeles"

        and: "headers.tz also falls back to the DEFAULT_TIME_ZONE constant"
        rawPosts[0].headers?.tz == "America/Los_Angeles"
    }

    // -------------------------------------------------------------------------
    // D: isLevoitClimateDevice() — Generic-driver dispatch filter
    //
    // This filter prevents non-Levoit VeSync devices (Etekcity plugs, Cosori
    // air fryers, etc.) from getting a malfunctioning Generic Levoit child.
    // Only devices whose model codes carry LAP-/LEH-/LV- prefixes, or one of
    // the legacy literal names (Core200S etc.), are attached as Generic.
    // -------------------------------------------------------------------------

    def "isLevoitClimateDevice() returns true for Levoit purifier/humidifier codes (D1)"(String code) {
        // D1: positive path — all known Levoit climate-class prefixes and literals.
        expect:
        driver.isLevoitClimateDevice(code) == true

        where:
        code << [
            "LAP-V201S-WUS",    // Vital 200S (US)
            "LAP-C601S-WEU",    // Core 600S (EU)
            "LEH-S601S-WUS",    // Superior 6000S (US)
            "LEH-S602S-WUSR",   // Superior 6000S variant
            "LV-PUR131S",       // older Levoit purifier
            "LV-RH131S",        // older Levoit humidifier
            "Core200S",         // legacy literal recognized by deviceType()
            "Core400S",
            "Vital200S"
        ]
    }

    def "isLevoitClimateDevice() returns false for non-Levoit VeSync device codes (D2)"(String code) {
        // D2: negative path — Etekcity, Cosori, and non-prefix codes should be
        // skipped so they never receive a malfunctioning Generic Levoit child.
        expect:
        driver.isLevoitClimateDevice(code) == false

        where:
        code << [
            "WS-WHM01",     // Etekcity smart switch
            "ESW15-USA",    // Etekcity smart plug
            "ESO15-TB",     // Etekcity outdoor outlet
            "CS158-AF",     // Cosori air fryer
            null,
            "",
            "unknown-thing"
        ]
    }

    // D3 (v2.0): asserted fan prefixes (LTF-/LPF-) returned false from
    // isLevoitClimateDevice(). SUPERSEDED by G1 in v2.1 which asserts the
    // opposite (now returns true) since the v2.1 release adds proper fan
    // drivers and extends the whitelist. D3 deleted to avoid same-input/
    // opposite-assertion test contradiction.

    // -------------------------------------------------------------------------
    // E: Defensive addChildDevice validation + self-heal pass
    //
    // Live-test finding: addChildDevice may return a non-null phantom handle if
    // the DNI was recently deleted and Hubitat's platform purge has not yet
    // completed. The parent now re-fetches via getChildDevice to confirm the
    // device is actually queryable before calling updateDataValue.
    //
    // updateDevices() now runs a self-heal pass early in its body that removes
    // state.deviceList entries whose getChildDevice returns null, so orphaned
    // polls clean themselves up between forceReinitialize calls.
    //
    // Architecture note for wireSandbox:
    //   addChildDevice is wired in wireSandbox to return a TestDevice AND register
    //   it in childDevices[dni] so getChildDevice can find it. For the phantom test
    //   (E1) we need addChildDevice to return a TestDevice but getChildDevice to
    //   return null for that same DNI — simulating a phantom handle. We override
    //   both inside each spec's given: block.
    // -------------------------------------------------------------------------

    def "getDevices() phantom addChildDevice: logError emitted and updateDataValue skipped (E1)"() {
        // E1: addChildDevice returns a non-null TestDevice (phantom), but getChildDevice
        // subsequently returns null for the same cid. The parent must log an error
        // and skip updateDataValue (the phantom handle must not be written to).
        given: "VeSync account returns one V601S device"
        settings.descriptionTextEnable = true
        settings.debugOutput = false
        settings.refreshInterval = 30
        state.token     = "tok-e1"
        state.accountID = "acc-e1"
        state.prefsSeeded = true
        def phantomDevice = new TestDevice()
        int updateDataValueCallCount = 0
        phantomDevice.metaClass.updateDataValue = { String k, String v -> updateDataValueCallCount++ }

        // addChildDevice: returns the phantom but does NOT register it in childDevices.
        // getChildDevice will therefore return null for this cid (simulating a phantom).
        driver.metaClass.addChildDevice = { String typeName, String dni, Map props ->
            // Deliberately do NOT put it in childDevices[dni] -- platform phantom
            phantomDevice
        }
        // getChildDevice: returns null for the phantom cid, real device for any other
        driver.metaClass.getChildDevice = { String dni ->
            childDevices[dni]  // phantomDevice is not in childDevices, so returns null
        }

        driver.metaClass.httpPost = { Map params, Closure callback ->
            def r = new Expando()
            r.status = 200
            r.data = [
                code: 0,
                result: [
                    code: 0,
                    list: [
                        [
                            deviceType  : "LEH-S601S-WUS",
                            deviceName  : "My Humidifier",
                            cid         : "phantom-cid-001",
                            configModule: "LEH-S601S-configModule",
                            uuid        : "uuid-phantom-001",
                            macID       : "AA:BB:CC:DD:EE:01"
                        ]
                    ],
                    total: 1
                ],
                traceId: "test-trace"
            ]
            callback(r)
        }

        when:
        driver.getDevices()

        then: "logError was called mentioning the phantom cid"
        testLog.errors.any { it =~ /addChildDevice for My Humidifier .* appeared to succeed but the device is not queryable/ }

        and: "updateDataValue was NOT called on the phantom handle"
        updateDataValueCallCount == 0
    }

    def "updateDevices() self-heal removes stale cid from state.deviceList (E2)"() {
        // E2: state.deviceList contains two entries — one has a live child device, one is
        // a ghost (getChildDevice returns null). updateDevices() should remove the ghost
        // and log an INFO message. The live entry must remain intact.
        given: "state.deviceList has one ghost cid and one real cid"
        settings.refreshInterval = 30
        settings.descriptionTextEnable = true
        state.prefsSeeded = true
        state.deviceList = [
            "vsaqGhost": "config-ghost",
            "vsaqReal":  "config-real"
        ]

        // Wire a real child device for vsaqReal, null for vsaqGhost
        def realDevice = new TestDevice()
        realDevice.typeName = "Levoit Vital 200S Air Purifier"
        realDevice.metaClass.update = { Map st, nl -> true }
        childDevices["vsaqReal"] = realDevice
        // vsaqGhost intentionally absent from childDevices -> getChildDevice returns null

        when:
        driver.updateDevices()

        then: "the ghost cid is removed from state.deviceList"
        !state.deviceList.containsKey("vsaqGhost")

        and: "the real cid is still present in state.deviceList"
        state.deviceList.containsKey("vsaqReal")

        and: "an INFO log was emitted identifying the stale entry"
        testLog.infos.any { it.contains("Removing stale device tracking entry for vsaqGhost") }
    }

    def "updateDevices() self-heal also removes the paired -nl entry for a stale cid (E3)"() {
        // E3: when a ghost cid has a paired night-light (-nl) entry in state.deviceList,
        // both must be removed by the self-heal pass.
        given: "state.deviceList has a ghost cid plus its -nl companion"
        settings.refreshInterval = 30
        settings.descriptionTextEnable = true
        state.prefsSeeded = true
        state.deviceList = [
            "vsaqGhost":     "config-ghost",
            "vsaqGhost-nl":  "config-ghost-nl"
        ]
        // Neither vsaqGhost nor vsaqGhost-nl is in childDevices (both ghosts)
        // The self-heal loop only iterates non-nl keys, but on finding vsaqGhost null
        // it must also remove vsaqGhost-nl.

        when:
        driver.updateDevices()

        then: "the ghost cid is removed"
        !state.deviceList.containsKey("vsaqGhost")

        and: "the paired -nl entry is also removed"
        !state.deviceList.containsKey("vsaqGhost-nl")
    }

    // -------------------------------------------------------------------------
    // F: deviceType() — v2.1 new model codes
    //
    // Each new driver introduced in v2.1 has one or more model codes that must
    // resolve to the correct internal dtype string in the parent's switch block.
    // -------------------------------------------------------------------------

    def "deviceType() recognizes LAP-V102S-WUS as V100S (Vital 100S, US)"() {
        expect:
        driver.deviceType("LAP-V102S-WUS") == "V100S"
    }

    def "deviceType() recognizes LAP-V102S-AEUR as V100S (Vital 100S, EU regional variant)"() {
        expect:
        driver.deviceType("LAP-V102S-AEUR") == "V100S"
    }

    def "deviceType() recognizes Classic300S string as A601S (Classic 300S Humidifier)"() {
        expect:
        driver.deviceType("Classic300S") == "A601S"
    }

    def "deviceType() recognizes LUH-A601S-WUSB as A601S (Classic 300S Humidifier, US variant)"() {
        expect:
        driver.deviceType("LUH-A601S-WUSB") == "A601S"
    }

    def "deviceType() recognizes LUH-O451S-WUS as O451S (OasisMist 450S, primary US code)"() {
        expect:
        driver.deviceType("LUH-O451S-WUS") == "O451S"
    }

    def "deviceType() recognizes LUH-O601S-WUS as O451S (OasisMist 600S US -- shares driver per device_map overlap)"() {
        // LUH-O601S-WUS maps to the same pyvesync class (VeSyncHumid200300S) as LUH-O451S-WUS.
        // Both share the OasisMist 450S driver until a separate 600S driver is warranted.
        expect:
        driver.deviceType("LUH-O601S-WUS") == "O451S"
    }

    def "deviceType() recognizes LTF-F422S-WUS as TOWERFAN (Tower Fan, US)"() {
        expect:
        driver.deviceType("LTF-F422S-WUS") == "TOWERFAN"
    }

    def "deviceType() recognizes LPF-R432S-AEU as PEDESTALFAN (Pedestal Fan, EU)"() {
        // Note: pyvesync fixture has a typo (LPF-R423S) but the real device code is LPF-R432S.
        expect:
        driver.deviceType("LPF-R432S-AEU") == "PEDESTALFAN"
    }

    // -------------------------------------------------------------------------
    // G: isLevoitClimateDevice() — v2.1 fan prefix additions
    //
    // v2.0 intentionally excluded LTF-* and LPF-* to avoid Generic children
    // being created before proper fan drivers existed. v2.1 adds proper fan
    // drivers and enables these prefixes in the whitelist. Spec D3 (which
    // asserted false for these codes) is now superseded by G1 (asserts true).
    // -------------------------------------------------------------------------

    def "isLevoitClimateDevice() returns true for LTF- and LPF- fan codes in v2.1 (G1)"(String code) {
        // G1: v2.1 adds Tower Fan (LTF-*) and Pedestal Fan (LPF-*) to the whitelist.
        // These were excluded in v2.0 (see spec D3 -- now superseded for these codes).
        // Any future unknown LTF-/LPF- model code falls through to Generic rather than
        // being skipped as non-Levoit.
        expect:
        driver.isLevoitClimateDevice(code) == true

        where:
        code << [
            "LTF-F422S-WUS",    // Tower Fan (US) -- specific known code
            "LTF-F422S-WUSR",   // Tower Fan (US refurb)
            "LTF-F999X-WUS",    // hypothetical future Tower Fan -- generic LTF- prefix match
            "LPF-R432S-AEU",    // Pedestal Fan (EU) -- specific known code
            "LPF-R432S-AUS",    // Pedestal Fan (AU)
            "LPF-R999X-WUS"     // hypothetical future Pedestal Fan -- generic LPF- prefix match
        ]
    }

    // -------------------------------------------------------------------------
    // H: updateDevices() method routing — v2.1 fan types
    //
    // Tower Fan -> getTowerFanStatus, Pedestal Fan -> getFanStatus.
    // Existing Humidifier and purifier fallthrough must remain unchanged.
    // -------------------------------------------------------------------------

    def "updateDevices() uses getTowerFanStatus for Tower Fan device (H1)"() {
        given: "state has a Tower Fan device"
        settings.refreshInterval = 30
        settings.descriptionTextEnable = false
        state.prefsSeeded = true
        state.deviceList = ["TOWERFAN-CID": "test-config-module"]

        def fanDevice = new TestDevice()
        fanDevice.typeName = "Levoit Tower Fan"
        fanDevice.metaClass.update = { Map st, nl -> true }
        childDevices["TOWERFAN-CID"] = fanDevice

        when:
        driver.updateDevices()

        then: "the bypass request body used getTowerFanStatus"
        capturedBypassBodies.any { it.payload?.method == "getTowerFanStatus" }
    }

    def "updateDevices() uses getFanStatus for Pedestal Fan device (H2)"() {
        given: "state has a Pedestal Fan device"
        settings.refreshInterval = 30
        settings.descriptionTextEnable = false
        state.prefsSeeded = true
        state.deviceList = ["PEDESTALFAN-CID": "test-config-module"]

        def fanDevice = new TestDevice()
        fanDevice.typeName = "Levoit Pedestal Fan"
        fanDevice.metaClass.update = { Map st, nl -> true }
        childDevices["PEDESTALFAN-CID"] = fanDevice

        when:
        driver.updateDevices()

        then: "the bypass request body used getFanStatus"
        capturedBypassBodies.any { it.payload?.method == "getFanStatus" }
    }

    def "updateDevices() uses getPurifierStatus for Vital 100S device (H3 -- purifier fallthrough)"() {
        given: "state has a Vital 100S purifier device"
        settings.refreshInterval = 30
        settings.descriptionTextEnable = false
        state.prefsSeeded = true
        state.deviceList = ["V100S-CID": "test-config-module"]

        def purifierDevice = new TestDevice()
        purifierDevice.typeName = "Levoit Vital 100S Air Purifier"
        purifierDevice.metaClass.update = { Map st, nl -> true }
        childDevices["V100S-CID"] = purifierDevice

        when:
        driver.updateDevices()

        then: "the bypass request body used getPurifierStatus (fallthrough -- no 'Humidifier', 'Tower Fan', or 'Pedestal Fan' in typeName)"
        capturedBypassBodies.any { it.payload?.method == "getPurifierStatus" }
    }

    def "updateDevices() uses getHumidifierStatus for Classic 300S device (H4 -- humidifier branch)"() {
        given: "state has a Classic 300S humidifier device"
        settings.refreshInterval = 30
        settings.descriptionTextEnable = false
        state.prefsSeeded = true
        state.deviceList = ["A601S-CID": "test-config-module"]

        def humDevice = new TestDevice()
        humDevice.typeName = "Levoit Classic 300S Humidifier"
        humDevice.metaClass.update = { Map st, nl -> true }
        childDevices["A601S-CID"] = humDevice

        when:
        driver.updateDevices()

        then: "the bypass request body used getHumidifierStatus"
        capturedBypassBodies.any { it.payload?.method == "getHumidifierStatus" }
    }

    def "updateDevices() uses getHumidifierStatus for OasisMist 450S device (H5 -- humidifier branch)"() {
        given: "state has an OasisMist 450S humidifier device"
        settings.refreshInterval = 30
        settings.descriptionTextEnable = false
        state.prefsSeeded = true
        state.deviceList = ["O451S-CID": "test-config-module"]

        def humDevice = new TestDevice()
        humDevice.typeName = "Levoit OasisMist 450S Humidifier"
        humDevice.metaClass.update = { Map st, nl -> true }
        childDevices["O451S-CID"] = humDevice

        when:
        driver.updateDevices()

        then: "the bypass request body used getHumidifierStatus"
        capturedBypassBodies.any { it.payload?.method == "getHumidifierStatus" }
    }

    def "updateDevices() routes all 5 v2.1 devices correctly in a single deviceList (H6 -- combined)"() {
        given: "state has all 5 new v2.1 device types"
        settings.refreshInterval = 30
        settings.descriptionTextEnable = false
        state.prefsSeeded = true
        state.deviceList = [
            "V100S-CID":      "cm-v100s",
            "A601S-CID":      "cm-a601s",
            "O451S-CID":      "cm-o451s",
            "TOWERFAN-CID":   "cm-towerfan",
            "PEDESTAL-CID":   "cm-pedestal"
        ]

        [
            ["V100S-CID",    "Levoit Vital 100S Air Purifier"],
            ["A601S-CID",    "Levoit Classic 300S Humidifier"],
            ["O451S-CID",    "Levoit OasisMist 450S Humidifier"],
            ["TOWERFAN-CID", "Levoit Tower Fan"],
            ["PEDESTAL-CID", "Levoit Pedestal Fan"]
        ].each { cid, typeName ->
            def dev = new TestDevice()
            dev.typeName = typeName
            dev.metaClass.update = { Map st, nl -> true }
            childDevices[cid] = dev
        }

        when:
        driver.updateDevices()

        then: "getPurifierStatus was used for Vital 100S"
        capturedBypassBodies.any { it.payload?.method == "getPurifierStatus" }

        and: "getHumidifierStatus was used for Classic 300S"
        capturedBypassBodies.count { it.payload?.method == "getHumidifierStatus" } >= 1

        and: "getTowerFanStatus was used for Tower Fan"
        capturedBypassBodies.any { it.payload?.method == "getTowerFanStatus" }

        and: "getFanStatus was used for Pedestal Fan"
        capturedBypassBodies.any { it.payload?.method == "getFanStatus" }

        and: "exactly 5 bypass requests were made"
        capturedBypassBodies.size() == 5
    }

    def "getDevices() skips non-Levoit device -- no addChildDevice call, INFO log emitted, cid absent from deviceList (D4)"() {
        // D4: integration test — a non-Levoit device on the VeSync account (here an
        // Etekcity smart switch) must not trigger addChildDevice and must not appear
        // in state.deviceList. An INFO-level skip log must be emitted so the user
        // understands why the device has no Hubitat child.
        given: "VeSync account returns one non-Levoit device (Etekcity WS-WHM01)"
        settings.descriptionTextEnable = true
        settings.debugOutput = false
        settings.refreshInterval = 30
        state.token     = "tok-d4"
        state.accountID = "acc-d4"
        state.prefsSeeded = true
        int addChildDeviceCallCount = 0

        // Override addChildDevice to count invocations (parent wireSandbox installs a
        // no-op that returns a TestDevice; we override here to also count calls).
        driver.metaClass.addChildDevice = { String typeName, String dni, Map props ->
            addChildDeviceCallCount++
            def dev = new TestDevice()
            dev.name = typeName
            childDevices[dni] = dev
            dev
        }

        // httpPost: return a device list containing only the Etekcity device.
        driver.metaClass.httpPost = { Map params, Closure callback ->
            def r = new Expando()
            r.status = 200
            r.data = [
                code: 0,
                result: [
                    code: 0,
                    list: [
                        [
                            deviceType  : "WS-WHM01",
                            deviceName  : "Kitchen Plug",
                            cid         : "etk-001",
                            configModule: "EtekcitySwitch",
                            uuid        : "uuid-etk-001",
                            macID       : "AA:BB:CC:DD:EE:FF"
                        ]
                    ],
                    total: 1
                ],
                traceId: "test-trace"
            ]
            callback(r)
        }

        when:
        driver.getDevices()

        then: "no addChildDevice call was made for the non-Levoit device"
        addChildDeviceCallCount == 0

        and: "an INFO log was emitted explaining the skip"
        testLog.infos.any { it.contains("Skipping unsupported device type: WS-WHM01") }

        and: "the device's cid is absent from state.deviceList"
        !(state.deviceList?.containsKey("etk-001"))
    }

    // -------------------------------------------------------------------------
    // RULE22 parity: every code recognized by deviceType() is also whitelisted
    // by isLevoitClimateDevice(). One canonical exemplar per deviceType() branch.
    // If this fails, either isLevoitClimateDevice() is missing an entry or
    // deviceType() added a new branch without updating the whitelist.
    // -------------------------------------------------------------------------

    @Unroll
    def "isLevoitClimateDevice covers every code recognized by deviceType: #code (RULE22 parity)"() {
        expect: "both methods agree: code is Levoit AND deviceType returns a non-null type"
        driver.isLevoitClimateDevice(code) == true
        driver.deviceType(code) != null

        where:
        code << [
            // LAP-* purifiers — Core and Vital lines
            "LAP-C201S-AUSR",       // Core 200S
            "LAP-C301S-WJP",        // Core 300S
            "LAP-C401S-WUSR",       // Core 400S
            "LAP-C601S-WUS",        // Core 600S
            "LAP-V201S-WUS",        // Vital 200S
            "LAP-V102S-WUS",        // Vital 100S
            // LEH-* humidifiers — Superior 6000S (regex branch)
            "LEH-S601S-WUS",        // Superior 6000S
            // LUH-* humidifiers — Classic 300S + OasisMist 450S
            "LUH-A601S-WUSB",       // Classic 300S (LUH-* literal)
            "LUH-O451S-WUS",        // OasisMist 450S
            "LUH-O601S-WUS",        // OasisMist 450S variant
            // LTF-* fans
            "LTF-F422S-WUS",        // Tower Fan
            // LPF-* fans
            "LPF-R432S-AEU",        // Pedestal Fan
            // Literal device names (older firmware)
            "Core200S",
            "Core300S",
            "Core400S",
            "Core600S",
            "Vital200S",
            "Classic300S",          // Added alongside LUH-* gap fix; some firmware reports this literal
        ]
    }

    // -------------------------------------------------------------------------
    // H7: TYPENAME_TO_METHOD map default fallback
    //
    // Any child whose typeName does not contain "Tower Fan", "Pedestal Fan", or
    // "Humidifier" must fall through to DEFAULT_POLL_METHOD ("getPurifierStatus").
    // The Generic Levoit Diagnostic Driver ("Levoit Generic Device") is the
    // canonical example: it contains none of the routing substrings, so the
    // parent polls it with getPurifierStatus. This is documented in the driver
    // readme as a known limitation for humidifier-shape Generic devices.
    // -------------------------------------------------------------------------

    def "updateDevices() falls back to getPurifierStatus for Generic Levoit Device (H7 -- map default)"() {
        // H7: TYPENAME_TO_METHOD.find returns null for "Levoit Generic Device" (no matching
        // key substring) → Elvis operator picks DEFAULT_POLL_METHOD = "getPurifierStatus".
        // Bit-identical to the old if-chain's else branch for the same typeName.
        given: "state has a Generic Levoit device"
        settings.refreshInterval = 30
        settings.descriptionTextEnable = false
        state.prefsSeeded = true
        state.deviceList = ["GENERIC-CID": "test-config-module"]

        def genericDevice = new TestDevice()
        genericDevice.typeName = "Levoit Generic Device"
        genericDevice.metaClass.update = { Map st, nl -> true }
        childDevices["GENERIC-CID"] = genericDevice

        when:
        driver.updateDevices()

        then: "the bypass request body used getPurifierStatus (DEFAULT_POLL_METHOD fallback)"
        capturedBypassBodies.any { it.payload?.method == "getPurifierStatus" }

        and: "no other poll method was used"
        !capturedBypassBodies.any { it.payload?.method == "getHumidifierStatus" }
        !capturedBypassBodies.any { it.payload?.method == "getTowerFanStatus" }
        !capturedBypassBodies.any { it.payload?.method == "getFanStatus" }
    }

    // =========================================================================
    // Section I — Bug Pattern #14: schedule()-based poll-cycle recovery
    //
    // Validates that the BP14 fix (replacing recursive runIn() with schedule()
    // cron) correctly arms persistent polling, self-heals pre-v2.2 installs,
    // and is idempotent once migrated.
    //
    // Also covers the cron-syntax branching fix (PR #4, Gemini review):
    // Quartz seconds-field range is 0-59; "0/N * * * * ?" is invalid for N >= 60.
    // setupPollSchedule() branches:
    //   interval < 60  => "0/${interval} * * * * ?" (seconds-resolution)
    //   interval >= 60 => "0 */${interval/60} * * * ?" (minutes-resolution)
    //
    // I1  — 30s (seconds cron, golden path / default)
    // I2  — null interval defaults to 30s seconds cron
    // I3  — pre-v2.2 migration
    // I4  — idempotency
    // I5  — 60s boundary (first value needing minutes cron)
    // I6  — 120s (minutes cron, 2-minute interval)
    // I7  — updateDevices() integration
    // I8  — sendBypassRequest() integration
    // I9  — 300s (minutes cron, 5-minute interval, README-recommended max)
    // I10 — 59s boundary (last valid seconds-resolution value)
    // I11 — 90s (non-multiple of 60: fires at 1min with WARN)
    //
    // NOTE: subscribe() and unsubscribe() are NOT used in the corrected BP14
    // implementation (they are app-only APIs; Bug Pattern #15). The HubitatSpec
    // base mock throws MissingMethodException for both. Any driver code that
    // accidentally re-introduces subscribe() calls will fail these specs.
    // =========================================================================

    def "setupPollSchedule() arms cron with correct interval and sets scheduleVersion (I1 -- golden path 30s)"() {
        // I1: normal operation -- setupPollSchedule() must register a seconds-resolution cron
        // for the default 30s interval (< 60) and set state.scheduleVersion = "2.2".
        given: "refreshInterval is 30 seconds (default, < 60 -- uses seconds-resolution cron)"
        settings.refreshInterval = 30
        settings.descriptionTextEnable = false

        List<List<String>> scheduleCalls = []
        driver.metaClass.schedule = { String cronExpr, String handler ->
            scheduleCalls << [cronExpr, handler]
        }
        driver.metaClass.unschedule = { Object[] args -> /* no-op */ }

        when:
        driver.setupPollSchedule()

        then: "schedule() was called with the correct cron and handler"
        scheduleCalls.size() == 1
        scheduleCalls[0][0] == "0/30 * * * * ?"
        scheduleCalls[0][1] == "updateDevices"

        and: "state.scheduleVersion is set to '2.2'"
        state.scheduleVersion == "2.2"
    }

    def "setupPollSchedule() uses minutes-resolution cron for interval == 60 (I5 -- 60s boundary)"() {
        // I5: seconds-field range is 0-59; "0/60 * * * * ?" is invalid Quartz cron.
        // For interval >= 60 the driver must switch to minutes-resolution cron.
        // 60s => "0 */1 * * * ?" (every 1 minute).
        given: "refreshInterval is 60 seconds (boundary: first value requiring minutes cron)"
        settings.refreshInterval = 60
        settings.descriptionTextEnable = false

        List<List<String>> scheduleCalls = []
        driver.metaClass.schedule = { String cronExpr, String handler ->
            scheduleCalls << [cronExpr, handler]
        }
        driver.metaClass.unschedule = { Object[] args -> /* no-op */ }

        when:
        driver.setupPollSchedule()

        then: "schedule() was called with minutes-resolution cron '0 */1 * * * ?'"
        scheduleCalls.size() == 1
        scheduleCalls[0][0] == "0 */1 * * * ?"
        scheduleCalls[0][1] == "updateDevices"

        and: "no WARN was emitted (60 is a clean multiple of 60)"
        testLog.warns.isEmpty()

        and: "state.scheduleVersion is set to '2.2'"
        state.scheduleVersion == "2.2"
    }

    def "setupPollSchedule() uses minutes-resolution cron for interval == 120 (I6 -- 2-minute interval)"() {
        // I6: 120s => "0 */2 * * * ?" (every 2 minutes).
        // Verifies that the minutes divisor is computed correctly for the 2-minute case.
        given: "refreshInterval is 120 seconds"
        settings.refreshInterval = 120
        settings.descriptionTextEnable = false

        List<List<String>> scheduleCalls = []
        driver.metaClass.schedule = { String cronExpr, String handler ->
            scheduleCalls << [cronExpr, handler]
        }
        driver.metaClass.unschedule = { Object[] args -> /* no-op */ }

        when:
        driver.setupPollSchedule()

        then: "schedule() was called with cron '0 */2 * * * ?'"
        scheduleCalls.size() == 1
        scheduleCalls[0][0] == "0 */2 * * * ?"
        scheduleCalls[0][1] == "updateDevices"

        and: "no WARN was emitted (120 is a clean multiple of 60)"
        testLog.warns.isEmpty()

        and: "state.scheduleVersion is set to '2.2'"
        state.scheduleVersion == "2.2"
    }

    def "setupPollSchedule() uses minutes-resolution cron for interval == 300 (I9 -- 5-minute interval)"() {
        // I9: 300s => "0 */5 * * * ?" (every 5 minutes).
        // README recommends 300s for low-traffic installs -- must produce valid cron.
        given: "refreshInterval is 300 seconds (README-recommended maximum)"
        settings.refreshInterval = 300
        settings.descriptionTextEnable = false

        List<List<String>> scheduleCalls = []
        driver.metaClass.schedule = { String cronExpr, String handler ->
            scheduleCalls << [cronExpr, handler]
        }
        driver.metaClass.unschedule = { Object[] args -> /* no-op */ }

        when:
        driver.setupPollSchedule()

        then: "schedule() was called with cron '0 */5 * * * ?'"
        scheduleCalls.size() == 1
        scheduleCalls[0][0] == "0 */5 * * * ?"
        scheduleCalls[0][1] == "updateDevices"

        and: "no WARN was emitted (300 is a clean multiple of 60)"
        testLog.warns.isEmpty()

        and: "state.scheduleVersion is set to '2.2'"
        state.scheduleVersion == "2.2"
    }

    def "setupPollSchedule() uses seconds-resolution cron for interval == 59 (I10 -- boundary below 60)"() {
        // I10: 59s is the last value in the valid seconds-field range.
        // Must use "0/59 * * * * ?" (seconds-resolution), not minutes cron.
        given: "refreshInterval is 59 seconds (boundary: last value using seconds cron)"
        settings.refreshInterval = 59
        settings.descriptionTextEnable = false

        List<List<String>> scheduleCalls = []
        driver.metaClass.schedule = { String cronExpr, String handler ->
            scheduleCalls << [cronExpr, handler]
        }
        driver.metaClass.unschedule = { Object[] args -> /* no-op */ }

        when:
        driver.setupPollSchedule()

        then: "schedule() was called with seconds-resolution cron '0/59 * * * * ?'"
        scheduleCalls.size() == 1
        scheduleCalls[0][0] == "0/59 * * * * ?"
        scheduleCalls[0][1] == "updateDevices"

        and: "state.scheduleVersion is set to '2.2'"
        state.scheduleVersion == "2.2"
    }

    def "setupPollSchedule() emits WARN for non-multiple-of-60 interval >= 60 (I11 -- imprecise interval)"() {
        // I11: interval 90s is not a multiple of 60. Driver must still arm a valid cron
        // (firing every floor(90/60)=1 minute) but emit a WARN so the user can correct it.
        // This prevents silent polling at a different rate than the user configured.
        // log.warn() routes through HubitatSpec's TestLog.warns -- no special mock needed.
        given: "refreshInterval is 90 seconds (non-multiple of 60)"
        settings.refreshInterval = 90
        settings.descriptionTextEnable = false

        List<List<String>> scheduleCalls = []
        driver.metaClass.schedule = { String cronExpr, String handler ->
            scheduleCalls << [cronExpr, handler]
        }
        driver.metaClass.unschedule = { Object[] args -> /* no-op */ }

        when:
        driver.setupPollSchedule()

        then: "schedule() was still called with floor(90/60)=1 minute cron"
        scheduleCalls.size() == 1
        scheduleCalls[0][0] == "0 */1 * * * ?"
        scheduleCalls[0][1] == "updateDevices"

        and: "a WARN was emitted mentioning the configured interval and the actual firing rate"
        testLog.warns.any { it.contains("90") && it.contains("1 minute") }

        and: "state.scheduleVersion is set to '2.2'"
        state.scheduleVersion == "2.2"
    }

    def "setupPollSchedule() defaults to 30s when refreshInterval is null (I2 -- null interval)"() {
        // I2: null-guard -- when refreshInterval is absent (first install before settings commit),
        // setupPollSchedule() must default to 30s rather than NPE.
        given: "refreshInterval is not configured"
        settings.remove('refreshInterval')

        List<List<String>> scheduleCalls = []
        driver.metaClass.schedule = { String cronExpr, String handler ->
            scheduleCalls << [cronExpr, handler]
        }
        driver.metaClass.unschedule = { Object[] args -> /* no-op */ }

        when:
        driver.setupPollSchedule()

        then: "schedule() was called with the 30-second default cron"
        scheduleCalls.size() == 1
        scheduleCalls[0][0] == "0/30 * * * * ?"
        scheduleCalls[0][1] == "updateDevices"

        and: "state.scheduleVersion is set to '2.2'"
        state.scheduleVersion == "2.2"
    }

    def "ensurePollWatchdog() migrates pre-v2.2 install on first call (I3 -- migration)"() {
        // I3: pre-v2.2 install -- state.scheduleVersion is absent.
        // ensurePollWatchdog() must call setupPollSchedule() and log a migration message.
        // After migration, state.scheduleVersion == "2.2".
        // No subscribe() call must be made (Bug Pattern #15 -- app-only API).
        given: "state has no scheduleVersion (simulates pre-v2.2 install)"
        settings.refreshInterval = 30
        settings.descriptionTextEnable = true
        state.remove('scheduleVersion')

        List<List<String>> scheduleCalls = []
        driver.metaClass.schedule = { String cronExpr, String handler ->
            scheduleCalls << [cronExpr, handler]
        }
        driver.metaClass.unschedule = { Object[] args -> /* no-op */ }

        when:
        driver.ensurePollWatchdog()

        then: "schedule() was called (setupPollSchedule ran)"
        !scheduleCalls.isEmpty()
        scheduleCalls.any { it[1] == "updateDevices" }

        and: "state.scheduleVersion is now '2.2'"
        state.scheduleVersion == "2.2"

        and: "a migration INFO log was emitted"
        testLog.infos.any { it.contains("BP14 migration") }

        and: "no exception was thrown (no subscribe() call hit the fail-fast mock)"
        noExceptionThrown()
    }

    def "ensurePollWatchdog() is idempotent when scheduleVersion is already '2.2' (I4 -- idempotency)"() {
        // I4: already-migrated install -- ensurePollWatchdog() must be a no-op on second call.
        // schedule() must NOT be called again; a double schedule() would create a second
        // concurrent cron chain on top of the existing one.
        given: "state.scheduleVersion already set to '2.2' (already migrated)"
        settings.refreshInterval = 30
        settings.descriptionTextEnable = false
        state.scheduleVersion = "2.2"

        int scheduleCallCount = 0
        driver.metaClass.schedule = { String cronExpr, String handler ->
            scheduleCallCount++
        }

        when: "ensurePollWatchdog called twice"
        driver.ensurePollWatchdog()
        driver.ensurePollWatchdog()

        then: "schedule() was not called (no migration needed)"
        scheduleCallCount == 0

        and: "no migration log was emitted"
        !testLog.infos.any { it.contains("BP14 migration") }
    }

    def "updateDevices() calls ensurePollWatchdog() after pref-seed (I7 -- integration)"() {
        // I7: integration test -- verifies that updateDevices() triggers the migration
        // path when state.scheduleVersion is absent (pre-v2.2 install scenario).
        // pref-seed fires first (state.prefsSeeded set), watchdog fires second.
        given: "pre-v2.2 state: scheduleVersion absent, deviceList empty (no HTTP calls)"
        settings.refreshInterval = 30
        settings.descriptionTextEnable = false
        state.prefsSeeded = true
        state.remove('scheduleVersion')
        state.deviceList = [:]

        List<List<String>> scheduleCalls = []
        driver.metaClass.schedule = { String cronExpr, String handler ->
            scheduleCalls << [cronExpr, handler]
        }
        driver.metaClass.unschedule = { Object[] args -> /* no-op */ }

        when:
        driver.updateDevices()

        then: "schedule() was called by ensurePollWatchdog()"
        scheduleCalls.any { it[1] == "updateDevices" }

        and: "state.scheduleVersion is now '2.2' (migrated)"
        state.scheduleVersion == "2.2"

        and: "no exception thrown (no subscribe() call hit the fail-fast mock)"
        noExceptionThrown()
    }

    def "sendBypassRequest() calls ensurePollWatchdog() and migrates pre-v2.2 installs (I8 -- direct-command migration)"() {
        // I8: direct-command migration path -- a user or Rule sends a device command while
        // polling is dead (post-reboot, pre-v2.2 cron). sendBypassRequest must call
        // ensurePollWatchdog() so polling is resurrected without user clicking Save Preferences.
        given: "pre-v2.2 state: scheduleVersion absent, driverReloading cleared"
        settings.refreshInterval = 30
        settings.descriptionTextEnable = false
        state.remove('scheduleVersion')
        state.remove('driverReloading')
        state.token     = "tok-i8"
        state.accountID = "acc-i8"
        def equip = new TestDevice()

        List<List<String>> scheduleCalls = []
        driver.metaClass.schedule = { String cronExpr, String handler ->
            scheduleCalls << [cronExpr, handler]
        }
        driver.metaClass.unschedule = { Object[] args -> /* no-op */ }

        when:
        driver.sendBypassRequest(equip, [method: "getPurifierStatus", source: "APP", data: [:]], { resp -> })

        then: "schedule() was called by ensurePollWatchdog() inside sendBypassRequest()"
        scheduleCalls.any { it[1] == "updateDevices" }

        and: "state.scheduleVersion is now '2.2' (migrated)"
        state.scheduleVersion == "2.2"

        and: "no exception thrown (no subscribe() call hit the fail-fast mock)"
        noExceptionThrown()
    }

    // =========================================================================
    // Section J — EU region support (v2.2 preview)
    //
    // Validates the getDeviceRegion() / getApiHost() helpers, the deviceRegion
    // preference, the updated() region-change handler, and the bypassV2 body
    // field reflection.
    //
    // EU support ships as preview -- no EU hardware live-verified.
    // These specs exercise the code paths without real network calls.
    // =========================================================================

    def "getDeviceRegion() returns 'US' when deviceRegion preference is unset (J1 -- default)"() {
        // J1: Elvis fallback -- when the deviceRegion preference has not been set (e.g. fresh
        // install or pre-v2.2 upgrade where the pref didn't exist), getDeviceRegion() must
        // return "US" so existing installs are unaffected.
        given: "deviceRegion preference is absent"
        settings.remove('deviceRegion')

        expect: "getDeviceRegion() returns 'US'"
        driver.getDeviceRegion() == "US"
    }

    def "getDeviceRegion() reads 'EU' from settings when preference is set (J2 -- EU setting)"() {
        // J2: preference read path -- when the user has set deviceRegion to "EU" in the
        // driver preferences, getDeviceRegion() must return that value.
        given: "deviceRegion preference is set to EU"
        settings.deviceRegion = "EU"

        expect: "getDeviceRegion() returns 'EU'"
        driver.getDeviceRegion() == "EU"
    }

    def "getApiHost() returns correct host for each region (J3 -- routing)"(String region, String expectedHost) {
        // J3: routing helper -- getApiHost() maps "US" to smartapi.vesync.com and
        // "EU" to smartapi.vesync.eu per pyvesync const.py. Any value other than
        // "EU" falls through to the US host (safe default).
        given: "deviceRegion preference is set"
        settings.deviceRegion = region

        expect: "getApiHost() returns the correct VeSync cloud host"
        driver.getApiHost() == expectedHost

        where:
        region | expectedHost
        "US"   | "smartapi.vesync.com"
        "EU"   | "smartapi.vesync.eu"
        null   | "smartapi.vesync.com"    // null → Elvis → "US" → US host
    }

    def "updated() clears state.token and state.accountID when region changes (J4 -- region-change handler)"(String fromRegion, String toRegion) {
        // J4: region-change handler -- when the user switches regions in either direction
        // (US→EU or EU→US), the stored token and accountID are invalid (cross-region tokens
        // are rejected by the VeSync API). updated() must clear both and log an INFO message.
        // initialize() is called via runIn(15) and is not exercised here (mocked to no-op).
        // Both directions are tested via the where: table to guard against asymmetric logic.
        given: "driver was previously using fromRegion, now switching to toRegion"
        settings.descriptionTextEnable = true
        settings.debugOutput = false
        settings.deviceRegion = toRegion
        settings.refreshInterval = 30
        state.lastRegion  = fromRegion
        state.token       = "${fromRegion.toLowerCase()}-region-token"
        state.accountID   = "acc-${fromRegion.toLowerCase()}-001"

        // Stub out runIn and unschedule to prevent real scheduling side-effects
        driver.metaClass.runIn   = { int delay, String handler -> /* no-op */ }
        driver.metaClass.unschedule = { Object[] args -> /* no-op */ }
        driver.metaClass.pauseExecution = { Long ms -> /* no-op */ }

        when:
        driver.updated()

        then: "state.token is cleared"
        !state.containsKey('token')

        and: "state.accountID is cleared"
        !state.containsKey('accountID')

        and: "state.lastRegion is updated to the new region"
        state.lastRegion == toRegion

        and: "an INFO log was emitted naming both the old and new region"
        testLog.infos.any { it.contains("region changed") && it.contains(fromRegion) && it.contains(toRegion) }

        where:
        fromRegion | toRegion
        "US"       | "EU"
        "EU"       | "US"
    }

    def "updated() does NOT clear state.token when region is unchanged (J5 -- no-op for same-region save)"() {
        // J5: no-op path -- when the user clicks Save Preferences without changing the region,
        // updated() must NOT clear the stored token. Clearing the token on every save would
        // force an unnecessary re-login on every preference change.
        given: "driver region is already US, no region change"
        settings.descriptionTextEnable = false
        settings.debugOutput = false
        settings.deviceRegion = "US"
        settings.refreshInterval = 30
        state.lastRegion  = "US"
        state.token       = "valid-us-token"
        state.accountID   = "acc-us-002"

        driver.metaClass.runIn   = { int delay, String handler -> /* no-op */ }
        driver.metaClass.unschedule = { Object[] args -> /* no-op */ }
        driver.metaClass.pauseExecution = { Long ms -> /* no-op */ }

        when:
        driver.updated()

        then: "state.token is preserved (no region change, no re-login needed)"
        state.token == "valid-us-token"

        and: "state.accountID is preserved"
        state.accountID == "acc-us-002"

        and: "no region-change INFO log was emitted"
        !testLog.infos.any { it.contains("region changed") }
    }

    def "updated() does NOT clear token on first-ever save when state.lastRegion is null (J7 -- first-save guard)"() {
        // J7: null-lastRegion guard -- on a fresh install or the very first updated() call
        // after a hub restore, state.lastRegion is null (no prior region recorded).
        // The guard `if (state.lastRegion != null && ...)` must skip the clear-and-relogin
        // path so users aren't forced to re-authenticate on first setup.
        // state.lastRegion must be set to the current preference value afterwards so that
        // subsequent region changes are detected correctly.
        given: "fresh install: state.lastRegion absent, token already populated (e.g. from a prior session)"
        settings.descriptionTextEnable = true
        settings.debugOutput = false
        settings.deviceRegion = "EU"
        settings.refreshInterval = 30
        state.remove('lastRegion')          // simulate first-ever save
        state.token     = "pre-existing-token"
        state.accountID = "pre-existing-acc"

        driver.metaClass.runIn   = { int delay, String handler -> /* no-op */ }
        driver.metaClass.unschedule = { Object[] args -> /* no-op */ }
        driver.metaClass.pauseExecution = { Long ms -> /* no-op */ }

        when:
        driver.updated()

        then: "token is NOT cleared (first-save guard skips the clear)"
        state.token == "pre-existing-token"

        and: "accountID is NOT cleared"
        state.accountID == "pre-existing-acc"

        and: "state.lastRegion is now seeded with the current preference value"
        state.lastRegion == "EU"

        and: "no region-change INFO log was emitted"
        !testLog.infos.any { it.contains("region changed") }
    }

    def "sendBypassRequest body.deviceRegion reflects the configured preference value (J6 -- body field)"() {
        // J6: bypassV2 body field -- the body sent to sendBypassRequest must carry the
        // getDeviceRegion() value in body.deviceRegion. Tested for both US (default/explicit)
        // and EU.
        given: "deviceRegion preference is set to EU"
        settings.deviceRegion = "EU"
        settings.descriptionTextEnable = false
        settings.debugOutput = false
        state.token     = "tok-j6"
        state.accountID = "acc-j6"
        state.scheduleVersion = "2.2"   // skip watchdog migration in this test
        def equip = new TestDevice()
        capturedBypassBodies.clear()

        when:
        driver.sendBypassRequest(equip, [method: "getPurifierStatus", source: "APP", data: [:]], { resp -> })

        then: "the request body carries deviceRegion 'EU' matching the preference"
        capturedBypassBodies.size() == 1
        capturedBypassBodies[0].deviceRegion == "EU"
    }

    // =========================================================================
    // Section K — Model code routing: v2.2 audit additions
    //
    // Six regional / variant model codes added to deviceType() by the v2.2 pyvesync
    // audit. All map to existing dtypes and existing drivers -- no new driver
    // behavior. Tests are a single parameterized spec so the where: table is the
    // single source of truth for the routing contract.
    //
    // Deferred (v2.3+ candidates): LAP-C301S-WAAA, LAP-C302S-WGC -- unknown-region
    // suffix codes; pyvesync supports them but suffix meaning is unclear. Not added
    // here to avoid silently misrouting if the API behavior differs.
    // =========================================================================

    def "deviceType() routes v2.2 audit model codes to correct dtype (K1)"(String code, String expected) {
        // K1: parameterized routing table for all 6 codes added in the v2.2 pyvesync audit.
        // Each row is a model code → expected dtype assertion.
        //
        // LAP-V201-AUSR (no S after V201): intentional typo SKU -- this is the literal code
        // the VeSync API emits for AU-market V201S hardware per pyvesync device_map.py.
        // The missing S is NOT a test typo; do not "fix" it.
        expect:
        driver.deviceType(code) == expected

        where:
        code                | expected
        "LUH-O451S-WEU"    | "O451S"   // EU OasisMist 450S -- same VeSyncHumid200300S class
        "LAP-V201S-AEUR"   | "V200S"   // EU V201S variant -- same VeSyncAirBypass class
        "LAP-V201-AUSR"    | "V200S"   // AU V201S: no-S typo SKU as emitted by VeSync API
        "LAP-C202S-WUSR"   | "200S"    // US Core 200S variant -- same VeSyncAirBypass class
        "LAP-V201S-WJP"    | "V200S"   // Japan V201S variant -- same VeSyncAirBypass class
        "LAP-C302S-WUSB"   | "300S"    // US Core 300S bundle SKU -- same VeSyncAirBypass class
    }

    // =========================================================================
    // Section L — Bug Pattern #16: debug auto-disable watchdog (parent)
    //
    // runIn(1800, "logDebugOff") in updated() is in-memory only. After a hub
    // reboot settings.debugOutput persists true but the runIn timer evaporates.
    // ensureDebugWatchdog() detects that condition and self-heals within one poll.
    //
    // Tests:
    //   L1 — updated() records state.debugEnabledAt when debug enabled
    //   L2 — updated() clears state.debugEnabledAt when debug disabled
    //   L3 — ensureDebugWatchdog() no-ops when debugOutput is false
    //   L4 — ensureDebugWatchdog() no-ops when elapsed < 30 min
    //   L5 — ensureDebugWatchdog() fires and disables when elapsed >= 30 min
    //   L6 — updateDevices() calls ensureDebugWatchdog() (integration)
    // =========================================================================

    def "updated() records state.debugEnabledAt when debugOutput is enabled (L1)"() {
        // L1: when the user enables debug and saves preferences, updated() must
        // record a timestamp so the watchdog can measure elapsed time post-reboot.
        given: "debugOutput is true"
        settings.debugOutput = true
        settings.refreshInterval = 30

        when:
        driver.updated()

        then: "state.debugEnabledAt is set to a recent timestamp"
        state.debugEnabledAt != null
        // Within 5 seconds of now() -- not checking exact value, just presence
        Math.abs(driver.now() - (state.debugEnabledAt as Long)) < 5000
    }

    def "updated() clears state.debugEnabledAt when debugOutput is disabled (L2)"() {
        // L2: when the user disables debug, updated() must remove debugEnabledAt
        // so the watchdog does not fire on a stale timestamp from a prior enable.
        given: "state has a prior timestamp and debugOutput is now false"
        state.debugEnabledAt = driver.now() - (60 * 60 * 1000)  // 60 min ago
        settings.debugOutput = false
        settings.refreshInterval = 30

        when:
        driver.updated()

        then: "state.debugEnabledAt is removed"
        !state.containsKey("debugEnabledAt")
    }

    def "ensureDebugWatchdog() is a no-op when debugOutput is false (L3)"() {
        // L3: watchdog should short-circuit immediately when debug is off.
        // No updateSetting call should be made.
        given:
        settings.debugOutput = false
        state.debugEnabledAt = driver.now() - (2 * 60 * 60 * 1000)  // 2 hours ago

        when:
        driver.ensureDebugWatchdog()

        then: "no setting update triggered"
        def debugCall = testDevice.settingsUpdates.find { it.name == "debugOutput" }
        debugCall == null
    }

    def "ensureDebugWatchdog() is a no-op when elapsed time is less than 30 min (L4)"() {
        // L4: within the 30-min window, watchdog must not fire.
        // This ensures a fresh debug enable is not immediately killed.
        given: "debug enabled 10 min ago (within window)"
        settings.debugOutput = true
        settings.descriptionTextEnable = true
        state.debugEnabledAt = driver.now() - (10 * 60 * 1000)  // 10 min ago

        when:
        driver.ensureDebugWatchdog()

        then: "no updateSetting call (watchdog did not fire)"
        def debugCall = testDevice.settingsUpdates.find { it.name == "debugOutput" }
        debugCall == null

        and: "no BP16 log emitted"
        !testLog.infos.any { it.contains("BP16") }
    }

    def "ensureDebugWatchdog() disables debug when elapsed >= 30 min (L5)"() {
        // L5: core BP16 behavior — when debugOutput has been true for >30 min
        // (runIn timer evaporated post-reboot), watchdog auto-disables it.
        given: "debug was enabled 35 min ago (past the 30-min threshold)"
        settings.debugOutput = true
        settings.descriptionTextEnable = true
        state.debugEnabledAt = driver.now() - (35 * 60 * 1000)  // 35 min ago

        when:
        driver.ensureDebugWatchdog()

        then: "updateSetting called to disable debugOutput"
        def debugCall = testDevice.settingsUpdates.find { it.name == "debugOutput" }
        debugCall != null
        debugCall.value == false

        and: "state.debugEnabledAt is cleared"
        !state.containsKey("debugEnabledAt")

        and: "BP16 INFO log emitted with the expected token"
        testLog.infos.any { it.contains("BP16 watchdog") }
    }

    def "updateDevices() calls ensureDebugWatchdog() during poll cycle (L6 -- integration)"() {
        // L6: integration -- ensureDebugWatchdog() must be invoked from updateDevices()
        // so the watchdog self-heals without user interaction.
        // We set debug to true with a stale timestamp and verify the watchdog fires.
        given: "debug stuck true for 40 min (post-reboot scenario)"
        settings.debugOutput = true
        settings.descriptionTextEnable = true
        settings.refreshInterval = 30
        state.debugEnabledAt = driver.now() - (40 * 60 * 1000)  // 40 min ago
        state.prefsSeeded = true
        state.scheduleVersion = "2.2"  // skip BP14 migration noise
        state.deviceList = [:]

        when:
        driver.updateDevices()

        then: "watchdog fired: debugOutput was disabled"
        def debugCall = testDevice.settingsUpdates.find { it.name == "debugOutput" }
        debugCall != null
        debugCall.value == false

        and: "BP16 INFO log emitted"
        testLog.infos.any { it.contains("BP16 watchdog") }
    }

    // =========================================================================
    // Section M — v2.2.1 patch: safeAddChildDevice + resp.msg guard
    //
    // Bug 1: UnknownDeviceTypeException from addChildDevice() was uncaught,
    //   crashing the discovery loop and preventing all subsequent devices from
    //   being added. Fixed by safeAddChildDevice() helper.
    //
    // Bug 2: resp?.msg on a non-null HttpResponseDecorator still throws
    //   MissingPropertyException because ?. only guards against null, not
    //   missing properties. Fixed by hasProperty() check.
    //
    // M1 -- unknown driver causes loop to continue with subsequent devices
    // M2 -- INFO log includes driver name and install URL
    // M3 -- Core 200S Light missing: main purifier still added (non-fatal)
    // M4 -- resp.msg guard: no exception when msg property absent
    // =========================================================================

    def "getDevices() continues past missing-driver device and adds next device in list (M1)"() {
        // M1: golden UnknownDeviceTypeException scenario. API returns two devices:
        // a V100S (driver not installed) followed by a V200S (driver installed).
        // Before the fix, the V100S throw would kill the loop and the V200S would
        // never be added. After the fix, both are attempted and only the V200S succeeds.
        given: "VeSync account returns two devices: V100S (driver missing) then V200S (driver present)"
        settings.descriptionTextEnable = true
        settings.debugOutput = false
        settings.refreshInterval = 30
        state.token     = "tok-m1"
        state.accountID = "acc-m1"
        state.prefsSeeded = true
        int v200sAddCount = 0

        // addChildDevice: throw for V100S, succeed for V200S
        driver.metaClass.addChildDevice = { String typeName, String dni, Map props ->
            if (typeName == "Levoit Vital 100S Air Purifier") {
                throw new com.hubitat.app.exception.UnknownDeviceTypeException(typeName)
            }
            def dev = new TestDevice()
            dev.name = typeName
            if (typeName == "Levoit Vital 200S Air Purifier") v200sAddCount++
            childDevices[dni] = dev
            dev
        }
        driver.metaClass.getChildDevice = { String dni -> childDevices[dni] }

        driver.metaClass.httpPost = { Map params, Closure callback ->
            def r = new Expando()
            r.status = 200
            r.data = [
                code: 0,
                result: [
                    code: 0,
                    list: [
                        [deviceType: "LAP-V102S-WUS", deviceName: "My Vital 100S",
                         cid: "cid-v100s", configModule: "cm-v100s", uuid: "uuid-v100s", macID: "AA:BB:CC:DD:EE:01"],
                        [deviceType: "LAP-V201S-WUS", deviceName: "My Vital 200S",
                         cid: "cid-v200s", configModule: "cm-v200s", uuid: "uuid-v200s", macID: "AA:BB:CC:DD:EE:02"]
                    ],
                    total: 2
                ],
                traceId: "test-trace"
            ]
            callback(r)
        }

        when:
        driver.getDevices()

        then: "V200S device was added despite V100S throwing UnknownDeviceTypeException"
        v200sAddCount == 1

        and: "V200S is present in childDevices"
        childDevices.containsKey("cid-v200s")

        and: "no exception was thrown from getDevices() (loop was not killed)"
        noExceptionThrown()
    }

    def "getDevices() logs INFO with driver name and install URL when driver not installed (M2)"() {
        // M2: verify that the INFO log produced by safeAddChildDevice() contains
        // the driver type name and the raw-GitHub install URL so the user knows
        // exactly what to install and where to get it.
        given: "VeSync account returns one Classic 300S device whose driver is not installed"
        settings.descriptionTextEnable = true
        settings.debugOutput = false
        settings.refreshInterval = 30
        state.token     = "tok-m2"
        state.accountID = "acc-m2"
        state.prefsSeeded = true

        driver.metaClass.addChildDevice = { String typeName, String dni, Map props ->
            throw new com.hubitat.app.exception.UnknownDeviceTypeException(typeName)
        }
        driver.metaClass.getChildDevice = { String dni -> childDevices[dni] }

        driver.metaClass.httpPost = { Map params, Closure callback ->
            def r = new Expando()
            r.status = 200
            r.data = [
                code: 0,
                result: [
                    code: 0,
                    list: [
                        [deviceType: "LUH-A601S-WUSB", deviceName: "My Classic 300S",
                         cid: "cid-a601s", configModule: "cm-a601s", uuid: "uuid-a601s", macID: "AA:BB:CC:DD:EE:03"]
                    ],
                    total: 1
                ],
                traceId: "test-trace"
            ]
            callback(r)
        }

        when:
        driver.getDevices()

        then: "INFO log contains the driver name"
        testLog.infos.any { it.contains("Levoit Classic 300S Humidifier") && it.contains("not installed") }

        and: "INFO log contains a raw.githubusercontent.com install URL"
        testLog.infos.any { it.contains("raw.githubusercontent.com") }

        and: "INFO log mentions clicking Resync Equipment again"
        testLog.infos.any { it.contains("Resync Equipment") }
    }

    def "getDevices() Core 200S: missing Light driver is non-fatal; main purifier is still added (M3)"() {
        // M3: the Core 200S branch adds the Light child FIRST, then the main purifier.
        // If the Light driver is not installed, the loop must NOT skip the main purifier --
        // only the Light add is skipped. The main purifier continues to be added as normal.
        // This is the critical correctness invariant for the 200S non-fatal behavior.
        given: "VeSync account returns one Core 200S device; Light driver missing, main driver present"
        settings.descriptionTextEnable = true
        settings.debugOutput = false
        settings.refreshInterval = 30
        state.token     = "tok-m3"
        state.accountID = "acc-m3"
        state.prefsSeeded = true
        int lightAddAttempts = 0
        int mainAddSuccesses = 0

        driver.metaClass.addChildDevice = { String typeName, String dni, Map props ->
            if (typeName == "Levoit Core200S Air Purifier Light") {
                lightAddAttempts++
                throw new com.hubitat.app.exception.UnknownDeviceTypeException(typeName)
            }
            // Main Core 200S purifier succeeds
            def dev = new TestDevice()
            dev.name = typeName
            if (typeName == "Levoit Core200S Air Purifier") mainAddSuccesses++
            childDevices[dni] = dev
            dev
        }
        driver.metaClass.getChildDevice = { String dni -> childDevices[dni] }

        driver.metaClass.httpPost = { Map params, Closure callback ->
            def r = new Expando()
            r.status = 200
            r.data = [
                code: 0,
                result: [
                    code: 0,
                    list: [
                        [deviceType: "Core200S", deviceName: "My Core 200S",
                         cid: "cid-200s", configModule: "cm-200s", uuid: "uuid-200s", macID: "AA:BB:CC:DD:EE:04"]
                    ],
                    total: 1
                ],
                traceId: "test-trace"
            ]
            callback(r)
        }

        when:
        driver.getDevices()

        then: "Light add was attempted (driver was missing)"
        lightAddAttempts == 1

        and: "main Core 200S purifier was still added successfully (non-fatal behavior)"
        mainAddSuccesses == 1

        and: "main purifier is present in childDevices"
        childDevices.containsKey("cid-200s")

        and: "INFO log mentions the missing Light driver"
        testLog.infos.any { it.contains("Levoit Core200S Air Purifier Light") && it.contains("not installed") }

        and: "no exception escaped getDevices()"
        noExceptionThrown()
    }

    def "updateDevices() error path does not throw when resp has no 'msg' property (M4)"() {
        // M4: Bug 2 regression test. updateDevices() error path contained:
        //   logError "No status returned from ${method}: ${resp?.msg}"
        // resp?.msg only guards against null resp; a non-null HttpResponseDecorator
        // without a msg property still throws MissingPropertyException. After the fix,
        // resp?.hasProperty('msg') ? resp.msg : '' is used instead.
        //
        // We simulate the scenario: httpPost returns HTTP 200 with outer code 0 but
        // resp.data.result == null (so the null-status branch is taken). The resp
        // Expando does NOT have a 'msg' property, reproducing the exact failure mode.
        given: "one device in deviceList; httpPost returns a null-result response with no msg property"
        settings.refreshInterval = 30
        settings.descriptionTextEnable = true
        settings.debugOutput = false
        state.prefsSeeded = true
        state.scheduleVersion = "2.2"
        state.deviceList = ["cid-msg-test": "cm-msg-test"]

        def mockDev = new TestDevice()
        mockDev.typeName = "Levoit Vital 200S Air Purifier"
        mockDev.metaClass.update = { Map st, nl -> true }
        childDevices["cid-msg-test"] = mockDev

        // Return a response with outer code 0 but result == null,
        // AND deliberately do NOT set a 'msg' property on the Expando.
        driver.metaClass.httpPost = { Map params, Closure callback ->
            def r = new Expando()
            r.status = 200
            r.data = [
                code: 0,
                result: null,   // triggers the null-status error path
                traceId: "test-trace"
            ]
            // Deliberately omit r.msg -- the Expando has no 'msg' property.
            // Before the fix, resp?.msg would throw MissingPropertyException here.
            callback(r)
        }

        when: "updateDevices() runs and hits the null-status error branch"
        driver.updateDevices()

        then: "no MissingPropertyException is thrown"
        noExceptionThrown()

        and: "an error log was emitted (the branch ran) without throwing"
        testLog.errors.any { it.contains("No status returned from") }
    }

    def "getDevices() logs missing-driver INFO exactly once when N devices need the same driver (M5)"() {
        // M5: dedup behavior. Two V100S devices (same missing driver). Before the fix,
        // safeAddChildDevice() would log 2 INFO messages. After the fix, exactly 1.
        // The second device is still skipped (returns null so the loop continues), but silently.
        given: "VeSync account returns two V100S devices; Vital 100S driver is not installed"
        settings.descriptionTextEnable = true
        settings.debugOutput = false
        settings.refreshInterval = 30
        state.token     = "tok-m5"
        state.accountID = "acc-m5"
        state.prefsSeeded = true
        int addCallCount = 0

        driver.metaClass.addChildDevice = { String typeName, String dni, Map props ->
            addCallCount++
            if (typeName == "Levoit Vital 100S Air Purifier") {
                throw new com.hubitat.app.exception.UnknownDeviceTypeException(typeName)
            }
            def dev = new TestDevice()
            dev.name = typeName
            childDevices[dni] = dev
            dev
        }
        driver.metaClass.getChildDevice = { String dni -> childDevices[dni] }

        driver.metaClass.httpPost = { Map params, Closure callback ->
            def r = new Expando()
            r.status = 200
            r.data = [
                code: 0,
                result: [
                    code: 0,
                    list: [
                        [deviceType: "LAP-V102S-WUS", deviceName: "Meadow Noise",
                         cid: "cid-v100s-1", configModule: "cm-v100s-1", uuid: "uuid-v100s-1", macID: "AA:BB:CC:DD:EE:01"],
                        [deviceType: "LAP-V102S-WUS", deviceName: "Willow Noise",
                         cid: "cid-v100s-2", configModule: "cm-v100s-2", uuid: "uuid-v100s-2", macID: "AA:BB:CC:DD:EE:02"]
                    ],
                    total: 2
                ],
                traceId: "test-trace"
            ]
            callback(r)
        }

        when:
        driver.getDevices()

        then: "addChildDevice was attempted for both devices (2 calls total)"
        addCallCount == 2

        and: "exactly ONE INFO log about the missing Vital 100S driver -- not two"
        def missingDriverLogs = testLog.infos.findAll {
            it.contains("Levoit Vital 100S Air Purifier") && it.contains("not installed")
        }
        missingDriverLogs.size() == 1

        and: "the INFO log references the first device by label (Meadow Noise -- first miss wins)"
        missingDriverLogs[0].contains("Meadow Noise")

        and: "state.warnedMissingDrivers is cleaned up after getDevices() completes"
        !state.containsKey('warnedMissingDrivers')

        and: "no exception escaped getDevices()"
        noExceptionThrown()
    }

    def "updateDevices() heartbeat descriptionText fires only on transition; steady-state is silent (M6)"() {
        // M6: heartbeat log dedup. First updateDevices() call transitions heartbeat
        // from null -> "synced" and logs INFO. Second call is steady-state synced->synced
        // and must emit sendEvent WITHOUT descriptionText so Hubitat generates no INFO log.
        // The "syncing" intermediate event (dropped in v2.2.1) must not appear at all.
        given: "driver initialized with no prior heartbeat value and one device in the list"
        settings.refreshInterval = 30
        settings.descriptionTextEnable = true
        settings.debugOutput = false
        state.prefsSeeded = true
        state.scheduleVersion = "2.2"
        state.deviceList = ["hb-cid": "hb-config"]

        def mockDev = new TestDevice()
        mockDev.typeName = "Levoit Vital 200S Air Purifier"
        mockDev.metaClass.update = { Map st, nl -> true }
        childDevices["hb-cid"] = mockDev

        when: "first updateDevices() call -- heartbeat transitions from null to 'synced'"
        driver.updateDevices()

        then: "exactly one heartbeat sendEvent fired (no 'syncing' intermediate)"
        def hbEventsAfterFirst = testDevice.allEvents("heartbeat")
        hbEventsAfterFirst.size() == 1
        hbEventsAfterFirst[0].value == "synced"

        and: "descriptionText is 'Heartbeat: synced' on first call (first-run, not 'recovered from unknown')"
        hbEventsAfterFirst[0].descriptionText == "Heartbeat: synced"

        and: "isStateChange is true on the first call"
        hbEventsAfterFirst[0].isStateChange == true

        when: "second updateDevices() call -- heartbeat is already 'synced' (steady-state)"
        driver.updateDevices()

        then: "a second heartbeat event was emitted (attribute still updates every cycle)"
        def hbEventsAfterSecond = testDevice.allEvents("heartbeat")
        hbEventsAfterSecond.size() == 2
        hbEventsAfterSecond[1].value == "synced"

        and: "descriptionText is null on the second call (no transition -- no log)"
        hbEventsAfterSecond[1].descriptionText == null

        and: "isStateChange is false on the second call"
        hbEventsAfterSecond[1].isStateChange == false

        and: "exactly one 'Heartbeat: synced' INFO log total -- steady-state is silent"
        testLog.infos.count { it.contains("Heartbeat: synced") } == 1
    }
}
