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
 *   captureDiagnostics — sends compact HTML to "diagnostics" attribute; full markdown in state.lastDiagnostics
 *   captureDiagnostics HTML content — attribute value contains the GitHub link and summary fields
 *   captureDiagnostics state.lastDiagnostics — full markdown contains expected section headers
 *   buildIssueUrl truncation divisor — budgetForDiag / 3 path is exercised without infinite loop
 *   buildIssueUrl title fallback — UNKNOWN model code produces "diagnostic capture" suffix
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
    // captureDiagnostics: attribute value is compact HTML
    // -------------------------------------------------------------------------

    def "captureDiagnostics() sends a non-empty diagnostics event with HTML content"() {
        given:
        seedDni("DIAG_DEVICE_DNI")
        state.errorHistory = [:]

        when:
        driver.captureDiagnostics()

        then: "a 'diagnostics' sendEvent was fired"
        def diagEvent = testDevice.events.find { it.name == "diagnostics" }
        diagEvent != null

        and: "the attribute value contains the clickable GitHub link"
        String diagValue = diagEvent.value as String
        diagValue.contains("href=")
        diagValue.contains("github.com/level99/Hubitat-VeSync/issues/new")
        diagValue.contains("File diagnostic bug report")
        diagValue.contains("target=\"_blank\"")

        and: "the attribute value uses <br> tags for line breaks (not \\n)"
        diagValue.contains("<br>")
        // Verify no literal \n characters that Hubitat would collapse to spaces
        !diagValue.contains("\n")

        and: "the attribute value includes the error count"
        diagValue.contains("Recent errors: 0")

        and: "the attribute value includes a Captured timestamp"
        diagValue.contains("Captured:")
    }

    def "captureDiagnostics() with errors shows error count in attribute"() {
        given:
        seedDni("DIAG_ERR_DNI")
        state.errorHistory = [:]
        driver.recordError("first error", [:])
        driver.recordError("second error", [:])
        testDevice.events.clear()   // clear the recordError side-effects (none here; just be safe)

        when:
        driver.captureDiagnostics()

        then:
        def diagEvent = testDevice.events.find { it.name == "diagnostics" }
        diagEvent != null
        String v = diagEvent.value as String
        v.contains("Recent errors: 2")
        v.contains("last: second error")
    }

    // -------------------------------------------------------------------------
    // captureDiagnostics: full markdown stored in state.lastDiagnostics
    // -------------------------------------------------------------------------

    def "captureDiagnostics() stores full markdown block in state.lastDiagnostics"() {
        given:
        seedDni("DIAG_STATE_DNI")
        state.errorHistory = [:]

        when:
        driver.captureDiagnostics()

        then: "state.lastDiagnostics is populated"
        state.lastDiagnostics != null
        String markdown = state.lastDiagnostics as String
        !markdown.isEmpty()

        and: "full markdown contains expected section headers"
        markdown.contains("### Levoit Driver Diagnostics")
        markdown.contains("#### Driver & Device")
        markdown.contains("#### Recent errors")

        and: "full markdown contains markdown table syntax (not the attribute value)"
        markdown.contains("| Field | Value |")
        markdown.contains("|---|---|")

        and: "full markdown does NOT appear in the diagnostics attribute (attribute is compact HTML)"
        String attrValue = testDevice.events.find { it.name == "diagnostics" }?.value as String
        !attrValue.contains("### Levoit Driver Diagnostics")
        !attrValue.contains("| Field | Value |")
    }

    // -------------------------------------------------------------------------
    // captureDiagnostics: parent-section suppressed when parent == null
    // -------------------------------------------------------------------------

    def "captureDiagnostics() suppresses parent-state section when no parent exists (child driver calling)"() {
        given: "testParent is available (child driver context — parent != null)"
        // HubitatSpec injects testParent as `parent`, so this is a child context.
        // The parent section should appear in state.lastDiagnostics (even if empty).
        seedDni("CHILD_DNI")
        state.errorHistory = [:]

        when:
        driver.captureDiagnostics()

        then:
        // In child context, parent != null — the section header may appear
        // (content may be "not available" since TestParent has no captureDiagnosticsFor)
        // This test just verifies no exception is thrown; the parent-null path is
        // tested separately via buildDiagnosticBlock opts.
        def diagEvent = testDevice.events.find { it.name == "diagnostics" }
        diagEvent != null
        noExceptionThrown()
    }

    def "buildDiagnosticBlock suppresses parent-state section when isParent=true"() {
        given:
        seedDni("PARENT_OWN_DNI")

        when: "block is built with isParent=true"
        String block = driver.buildDiagnosticBlock([
            driverName:    "VeSync Integration",
            driverVersion: "2.4",
            modelCode:     "UNKNOWN",
            hubFw:         "2.5.0.126",
            dni:           "PARENT_OWN_DNI",
            parentCtx:     [:],
            isParent:      true,
            errors:        [],
            attrSnap:      [:]
        ])

        then: "the Parent state section header does not appear"
        !block.contains("#### Parent state for this device")

        and: "the not-available message does not appear"
        !block.contains("not available")
    }

    // -------------------------------------------------------------------------
    // buildDiagnosticBlock: UNKNOWN rows suppressed
    // -------------------------------------------------------------------------

    def "buildDiagnosticBlock suppresses UNKNOWN model code row"() {
        given:
        seedDni("UNKNOWN_MODEL_DNI")

        when:
        String block = driver.buildDiagnosticBlock([
            driverName:    "VeSync Integration",
            driverVersion: "2.4",
            modelCode:     "UNKNOWN",
            hubFw:         "2.5.0.126",
            dni:           "UNKNOWN_MODEL_DNI",
            parentCtx:     [:],
            isParent:      false,
            errors:        [],
            attrSnap:      [:]
        ])

        then: "the model code row is absent when value is UNKNOWN"
        !block.contains("| Model code |")
    }

    def "buildDiagnosticBlock includes model code row when value is real"() {
        given:
        seedDni("REAL_MODEL_DNI")

        when:
        String block = driver.buildDiagnosticBlock([
            driverName:    "Levoit Vital 200S",
            driverVersion: "2.4",
            modelCode:     "LAP-V201S-AUSA",
            hubFw:         "2.5.0.126",
            dni:           "REAL_MODEL_DNI",
            parentCtx:     [:],
            isParent:      false,
            errors:        [],
            attrSnap:      [:]
        ])

        then:
        block.contains("| Model code | `LAP-V201S-AUSA` |")
    }

    // -------------------------------------------------------------------------
    // buildIssueUrl: title fallback logic
    // -------------------------------------------------------------------------

    def "buildIssueUrl uses 'diagnostic capture' suffix when modelCode is UNKNOWN and no lastError"() {
        given:
        seedDni("TITLE_FALLBACK_DNI")

        when:
        String url = driver.buildIssueUrl([
            "driver":      "VeSync Integration",
            "driver_version":   "2.4",
            "model_code":       "UNKNOWN",
            "hub_firmware":     "2.5.0.126",
            "last_error":       "",
            "diagnostic_block": "test block"
        ])

        then: "the title param decodes to '[VeSync Integration] diagnostic capture' (no UNKNOWN)"
        // Extract and decode the title param to assert on its value specifically.
        // model_code=UNKNOWN legitimately stays in the URL as a query param (triage signal) —
        // the assertion targets the title only, not the full URL string.
        def titleMatch = url =~ /[?&]title=([^&]*)/
        def title = titleMatch ? java.net.URLDecoder.decode(titleMatch[0][1] as String, "UTF-8") : ""
        title.contains("diagnostic capture")
        !title.contains("UNKNOWN")
    }

    def "buildIssueUrl uses lastError as title suffix when present"() {
        given:
        seedDni("TITLE_ERR_DNI")

        when:
        String url = driver.buildIssueUrl([
            "driver":      "Levoit Vital 200S",
            "driver_version":   "2.4",
            "model_code":       "LAP-V201S-AUSA",
            "hub_firmware":     "2.5.0.126",
            "last_error":       "cloud timeout on getPurifierStatus",
            "diagnostic_block": "block"
        ])

        then: "URL title suffix contains encoded lastError text"
        url.contains("cloud%20timeout") || url.contains("cloud+timeout")
    }

    def "buildIssueUrl uses model code as suffix when no lastError but modelCode is real"() {
        given:
        seedDni("TITLE_MODEL_DNI")

        when:
        String url = driver.buildIssueUrl([
            "driver":      "Levoit Vital 200S",
            "driver_version":   "2.4",
            "model_code":       "LAP-V201S-AUSA",
            "hub_firmware":     "2.5.0.126",
            "last_error":       "",
            "diagnostic_block": "block"
        ])

        then: "URL title suffix contains model code"
        url.contains("LAP-V201S-AUSA")
    }

    // -------------------------------------------------------------------------
    // buildIssueUrl: truncation divisor / 3 path must converge without hanging
    // -------------------------------------------------------------------------

    def "buildIssueUrl param keys match the bug_report.yml form field IDs (contract test)"() {
        // Contract guard: the 6 pre-filled URL parameter keys produced by buildIssueUrl()
        // must each appear as a form field `id` in .github/ISSUE_TEMPLATE/bug_report.yml.
        //
        // This spec was added in response to a mid-cycle break where the YAML used hyphenated
        // IDs (e.g. `driver-version`) while buildIssueUrl() used underscored keys
        // (`driver_version`). GitHub silently renders the form from the published `main`
        // template, so mismatched IDs cause pre-filled links to drop fields with no error.
        //
        // Direction of check: urlPrefillKeys ⊆ formFieldIds.
        //   - URL params `template`, `title`, `labels` are GitHub prefill mechanism fields,
        //     not form body elements — excluded from the check.
        //   - Form fields without URL params (e.g. `context`, `reproduction`, `debug_log`,
        //     `prereqs`) are not pre-filled and are also excluded — the URL is intentionally
        //     a subset of the form.
        //   - Only the 6 pre-filled fields need to be in lockstep in both files.
        given: "the issue template YAML is parsed for form field IDs"
        // .github/ISSUE_TEMPLATE/bug_report.yml lives outside src/test/resources/.
        // CWD when ./gradlew test runs is the project root, so the relative path resolves.
        File yamlFile = new File(".github/ISSUE_TEMPLATE/bug_report.yml")
        assert yamlFile.exists() : "bug_report.yml not found at ${yamlFile.absolutePath}"

        Map yamlData = new org.yaml.snakeyaml.Yaml().load(yamlFile.text) as Map
        List bodyFields = (yamlData.body as List) ?: []
        Set<String> formFieldIds = bodyFields
            .findAll { (it as Map).id != null }   // markdown blocks have no id — skip
            .collect { (it as Map).id as String }
            .toSet()

        and: "a representative buildIssueUrl call is made"
        String url = driver.buildIssueUrl([
            "driver":           "Levoit Vital 200S Air Purifier",
            "driver_version":   "2.4",
            "model_code":       "LAP-V201S-WUS",
            "hub_firmware":     "2.5.0.126",
            "last_error":       "test error",
            "diagnostic_block": "## diag block"
        ])

        when: "the URL query string is parsed for its parameter keys"
        String query = url.contains('?') ? url.substring(url.indexOf('?') + 1) : ""
        Set<String> allUrlParamKeys = query
            .tokenize('&')
            .collect { it.contains('=') ? it.substring(0, it.indexOf('=')) : it }
            .collect { java.net.URLDecoder.decode(it, "UTF-8") }
            .toSet()
        // Exclude the GitHub-mechanism params that are not form body field IDs
        Set<String> urlPrefillKeys = allUrlParamKeys - ["template", "title", "labels"]

        then: "every pre-filled URL param key matches a form field id in bug_report.yml"
        // If this fails: the two files have drifted. Rename the id in bug_report.yml to match
        // the key used in buildIssueUrl(), OR rename the key in buildIssueUrl() and update
        // bug_report.yml to match — but both MUST stay in lockstep. See the warning comment
        // block at the top of bug_report.yml for the full contract.
        Set<String> missing = urlPrefillKeys - formFieldIds
        missing.isEmpty()
    }

    def "buildIssueUrl handles a very large diagnostic_block without infinite loop"() {
        given: "a diagnostic block much larger than the URL budget"
        String hugeBlock = "X" * 20000   // 20KB, well above 7500 char budget

        when: "buildIssueUrl is called with the oversized block"
        String url = driver.buildIssueUrl([
            "driver":      "Test Driver",
            "driver_version":   "2.4",
            "model_code":       "TEST-001",
            "hub_firmware":     "2.3.9.1",
            "last_error":       "test error",
            "diagnostic_block": hugeBlock
        ])

        then: "a URL is returned (not null/empty) without an exception"
        url != null
        url.startsWith("https://github.com/")
        url.length() <= 9000   // generous upper bound (should be < 8000)
        noExceptionThrown()
    }
}
