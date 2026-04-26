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
}
