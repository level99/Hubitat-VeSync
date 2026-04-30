package drivers

import support.HubitatSpec

/**
 * Unit tests for LevoitDiagnosticsLib.groovy library methods.
 *
 * The library is tested through a real driver that includes it (LevoitVital200S.groovy),
 * because Hubitat libraries are not standalone — they are inlined into the driver at
 * load time. HubitatSpec.loadDriverClass() handles the #include resolution.
 *
 * Covers:
 *   recordError 2-arg — stores entry under device.deviceNetworkId slot
 *   recordError 3-arg override DNI — stores entry under the supplied overrideDni, NOT the device DNI
 *   recordError ring-buffer FIFO — 11th entry evicts oldest; slot stays at 10
 *   captureDiagnostics — sends a "diagnostics" event containing key sections
 *   buildIssueUrl truncation divisor — budgetForDiag / 3 path is exercised without infinite loop
 */
class LevoitDiagnosticsLibSpec extends HubitatSpec {

    @Override
    String driverSourcePath() {
        // Load through any driver that #includes the library.
        // Vital 200S is a canonical V2-line driver with #include level99.LevoitDiagnostics.
        "Drivers/Levoit/LevoitVital200S.groovy"
    }

    @Override
    Map defaultSettings() {
        return [descriptionTextEnable: true, debugOutput: false]
    }

    // -------------------------------------------------------------------------
    // Helper: seed device DNI
    // -------------------------------------------------------------------------

    private void seedDni(String dni) {
        // TestDevice.deviceNetworkId is the property the library reads.
        testDevice.deviceNetworkId = dni
    }

    // -------------------------------------------------------------------------
    // recordError 2-arg: stores under device DNI
    // -------------------------------------------------------------------------

    def "recordError(msg, ctx) stores entry under device DNI slot"() {
        given: "device has a known DNI and empty error history"
        seedDni("PARENT_00:11:22:33:44:55")
        state.errorHistory = [:]

        when:
        driver.recordError("test error alpha", [site: "testSite"])

        then: "entry is stored under the device DNI key"
        def history = state.errorHistory as Map
        history["PARENT_00:11:22:33:44:55"] != null

        and: "entry contains the message and context"
        def slot = history["PARENT_00:11:22:33:44:55"] as List
        slot.size() == 1
        slot[0].msg == "test error alpha"
        (slot[0].ctx as Map).site == "testSite"
    }

    // -------------------------------------------------------------------------
    // recordError 3-arg: overrideDni routes to child slot
    // -------------------------------------------------------------------------

    def "recordError(msg, ctx, overrideDni) stores under overrideDni, not device DNI"() {
        given: "parent device DNI and a child DNI"
        String parentDni = "PARENT_00:11:22:33:44:55"
        String childDni  = "child-dni-foo"
        seedDni(parentDni)
        state.errorHistory = [:]

        when: "recordError is called with the child DNI as overrideDni"
        driver.recordError("No status returned from getHumidifierStatus", [site: "updateDevices"], childDni)

        then: "entry is stored under childDni, NOT parentDni"
        def history = state.errorHistory as Map
        history[childDni] != null
        history[parentDni] == null   // parent slot untouched

        and: "the child slot has the correct message"
        def slot = history[childDni] as List
        slot.size() == 1
        slot[0].msg.contains("No status returned")
    }

    def "recordError 3-arg with null overrideDni falls back to device DNI"() {
        given:
        seedDni("PARENT_AA:BB:CC:DD:EE:FF")
        state.errorHistory = [:]

        when: "overrideDni is explicitly null"
        driver.recordError("fallback test", [site: "x"], null)

        then: "entry stored under device DNI"
        def history = state.errorHistory as Map
        history["PARENT_AA:BB:CC:DD:EE:FF"] != null
    }

    // -------------------------------------------------------------------------
    // Ring-buffer FIFO eviction at 10 entries
    // -------------------------------------------------------------------------

    def "recordError FIFO: 11th call evicts oldest; slot stays at 10 entries"() {
        given:
        seedDni("TEST_DNI")
        state.errorHistory = [:]

        when: "11 errors are recorded"
        11.times { i ->
            driver.recordError("error_${i}", [idx: i])
        }

        then: "slot has exactly 10 entries"
        def slot = (state.errorHistory as Map)["TEST_DNI"] as List
        slot.size() == 10

        and: "oldest entry (error_0) was evicted; most recent (error_10) is present"
        slot.every { !(it.msg == "error_0") }
        slot.any   {  it.msg == "error_10" }
    }

    // -------------------------------------------------------------------------
    // captureDiagnostics: sends a diagnostics event
    // -------------------------------------------------------------------------

    def "captureDiagnostics() sends a non-empty diagnostics event"() {
        given:
        seedDni("DIAG_DEVICE_DNI")
        state.errorHistory = [:]
        // Ensure testParent.captureDiagnosticsFor is available (returns empty map if absent)
        // TestParent's default implementation returns [:] for unknown methods

        when:
        driver.captureDiagnostics()

        then: "a 'diagnostics' sendEvent was fired"
        def diagEvent = testDevice.events.find { it.name == "diagnostics" }
        diagEvent != null

        and: "the diagnostics value is non-empty and contains expected section headers"
        String diagValue = diagEvent.value as String
        diagValue.contains("Levoit Driver Diagnostics")
        diagValue.contains("Driver & Device")
        diagValue.contains("Recent errors")
    }

    // -------------------------------------------------------------------------
    // buildIssueUrl: truncation divisor / 3 path must converge without hanging
    // -------------------------------------------------------------------------

    def "buildIssueUrl handles a very large diagnostic-block without infinite loop"() {
        given: "a diagnostic block much larger than the URL budget"
        String hugeBlock = "X" * 20000   // 20KB, well above 7500 char budget

        when: "buildIssueUrl is called with the oversized block"
        String url = driver.buildIssueUrl([
            "driver-name":      "Test Driver",
            "driver-version":   "2.3",
            "model-code":       "TEST-001",
            "hub-firmware":     "2.3.9.1",
            "last-error":       "test error",
            "diagnostic-block": hugeBlock
        ])

        then: "a URL is returned (not null/empty) without an exception"
        url != null
        url.startsWith("https://github.com/")
        url.length() <= 9000   // generous upper bound (should be < 8000)
        noExceptionThrown()
    }
}
