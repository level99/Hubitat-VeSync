package support

/**
 * Minimal HTTP response proxy for driver unit tests.
 *
 * The `hubBypass` helper in every child driver calls `parent.sendBypassRequest`
 * with a closure `{ resp -> rspObj = [status: resp?.status, data: resp?.data] }`.
 * TestParent drives this closure with a TestHttpResponse wrapping a Map.
 *
 * This class simply delegates `status` and `data` reads to the backing map,
 * mirroring the shape drivers expect from the real Hubitat HTTP response object.
 */
class TestHttpResponse {
    private final Map backing

    TestHttpResponse(Map backing) {
        this.backing = backing ?: [:]
    }

    Integer getStatus() {
        def s = backing.status
        return s == null ? 200 : (s as Integer)
    }

    def getData() {
        backing.data
    }

    // Some Core-line drivers call resp.msg on error paths.
    String getMsg() { "" }
}
