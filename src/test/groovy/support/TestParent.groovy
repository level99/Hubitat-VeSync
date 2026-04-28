package support

/**
 * Minimal parent-driver proxy for child-driver unit tests.
 *
 * Child drivers call `parent.sendBypassRequest(device, payload, closure)` to
 * send API commands and receive responses. This class captures the call and
 * optionally drives the closure with a canned response.
 *
 * Approach:
 *   - `sendBypassRequest` records the most-recent call in `lastRequest`.
 *   - If `cannedResponse` is non-null, the closure is immediately called with
 *     a TestHttpResponse wrapping the canned data. This allows:
 *       1. Command methods (on, setMode, etc.) — set cannedResponse to a
 *          success stub before calling the method; assert events after.
 *       2. Self-fetch path — likewise; the test drives the response.
 *   - If `cannedResponse` is null, the closure is called with a 200-OK stub
 *     that has no data — makes httpOk() return true (inner code null = ok).
 *
 * For tests that want to simulate API failure, set `cannedResponse` to a
 * response with status 500 or with result.code = -1.
 *
 * `cannedResponse` is reset to `null` after each `sendBypassRequest` call to
 * avoid bleed-across calls within one test. Tests that need multiple successful
 * calls simply set it repeatedly, or leave it null to accept the 200-ok default.
 *
 * For the parent driver's own tests (VeSyncIntegrationSpec), this class is
 * not used — the parent driver is loaded separately and child devices are
 * mocked via TestDevice with a `typeName` set.
 */
class TestParent {
    // Captured on each sendBypassRequest call
    Map lastRequest = null
    List<Map> allRequests = []

    // Pre-programmed response. Reset after each call.
    Map cannedResponse = null

    /**
     * Sequential response queue. When non-empty, each sendBypassRequest call
     * pops the first element and uses it as the response. Once exhausted, falls
     * through to cannedResponse (then defaultOkResponse). Backward-compatible:
     * tests using cannedResponse only are unaffected.
     *
     * Usage (multi-call tests):
     *   testParent.requestResponses = [rejectResp, acceptResp]
     *   driver.setMode("auto")
     *   // first call gets rejectResp, second gets acceptResp
     */
    List<Map> requestResponses = []

    /**
     * The parent driver's sendBypassRequest signature passes: (device, payloadMap, closure).
     * Children call it as:
     *   parent.sendBypassRequest(device, [method:..., source:..., data:...]) { resp -> ... }
     */
    void sendBypassRequest(def device, Map payload, Closure callback) {
        lastRequest = payload.clone()
        allRequests << payload.clone()

        Map resp
        if (requestResponses) {
            resp = requestResponses.remove(0)  // pop from front; queue drains call-by-call
        } else {
            resp = cannedResponse ?: defaultOkResponse()
            cannedResponse = null  // reset after use — no cross-call bleed
        }

        callback(new TestHttpResponse(resp))
    }

    /** Convenience: build a success response with device-level data. */
    static Map successResponse(Map deviceData) {
        return [
            status: 200,
            data: [
                code: 0,
                result: [
                    code: 0,
                    result: deviceData,
                    traceId: "test-trace"
                ],
                traceId: "test-trace"
            ]
        ]
    }

    /**
     * Double-wrapped success response (humidifier shape).
     * Superior 6000S wraps one extra layer:
     *   data.result.result = { actual device fields }
     */
    static Map doubleWrappedSuccessResponse(Map deviceData) {
        return [
            status: 200,
            data: [
                code: 0,
                result: [
                    code: 0,
                    result: [
                        code: 0,
                        result: deviceData,
                        traceId: "test-trace-inner"
                    ],
                    traceId: "test-trace"
                ],
                traceId: "test-trace"
            ]
        ]
    }

    /** Error response with inner code -1 (device-side rejection). */
    static Map innerErrorResponse() {
        return [
            status: 200,
            data: [
                code: 0,
                result: [
                    code: -1,
                    result: [:],
                    traceId: "test-trace"
                ],
                traceId: "test-trace"
            ]
        ]
    }

    /** HTTP-level failure (connection/server error). */
    static Map httpErrorResponse(int status = 500) {
        return [status: status, data: null]
    }

    private static Map defaultOkResponse() {
        return [
            status: 200,
            data: [
                code: 0,
                result: [
                    code: 0,
                    result: [:],
                    traceId: "test-trace"
                ],
                traceId: "test-trace"
            ]
        ]
    }

    void reset() {
        lastRequest = null
        allRequests.clear()
        cannedResponse = null
        requestResponses = []
    }

    // Parent is also called for child lookup during Core 200S night-light path.
    // Return null by default; override in specs that need it.
    def getChildDevice(String dni) { null }

    // Parent's updateDevices() is called by the Light driver's no-arg update().
    // No-op in tests.
    void updateDevices() {}
}
