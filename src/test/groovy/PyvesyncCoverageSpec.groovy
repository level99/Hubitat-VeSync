/**
 * PyvesyncCoverageSpec — payload field-name parity gate between our driver fixtures
 * and pyvesync's canonical API fixtures.
 *
 * Purpose:
 *   Catches the "pyvesync updated a field name, our driver silently sends the wrong one"
 *   failure class. When pyvesync changes a payload field (e.g. `levelIdx` → `levelId`),
 *   a test here fails immediately — before any driver code is deployed.
 *
 * How it works:
 *   For each driver that has a pyvesync canonical fixture, this spec loads:
 *     (a) Our driver fixture from tests/fixtures/<file>.yaml    (requests.<op>.payload)
 *     (b) pyvesync's vendored fixture from tests/pyvesync-fixtures/<file>.yaml
 *                                                               (<op>.json_object.payload)
 *
 *   For each operation name that appears in BOTH fixtures, we assert:
 *     1. payload.method matches exactly
 *     2. payload.data key set matches exactly (field names; values not compared)
 *
 * Skipping rules:
 *   - Operations in our fixture but NOT in pyvesync's: skipped (driver-only extras;
 *     e.g. our Timer commands, child-lock commands, EverestAir VENT_ANGLE).
 *   - Operations in pyvesync's fixture but NOT in ours: skipped (pyvesync covers
 *     more scenarios than we implement; e.g. pyvesync's nightlight tests we don't have).
 *   - The intersection (present in both) is what is asserted.
 *
 * Vendored fixtures:
 *   tests/pyvesync-fixtures/ contains fixture files pinned at pyvesync tag 3.4.2
 *   (commit 01196b9, 2026-04-16). Refresh protocol: see tests/pyvesync-fixtures/README.md.
 *
 * Expected test count: one iteration per (driver, operation) pair where the operation
 * name appears in both our fixture's 'requests:' section AND pyvesync's vendored fixture.
 * Drivers where our fixture uses different naming conventions (e.g. EverestAir 'display_on'
 * vs pyvesync 'turn_on_display') will have a smaller intersection than the total operation
 * count of either fixture. Run './gradlew test --tests PyvesyncCoverageSpec' to see the count.
 *
 * pyvesync tag: 3.4.2 (commit 01196b9 — 2026-04-16)
 */

import org.yaml.snakeyaml.Yaml
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

class PyvesyncCoverageSpec extends Specification {

    @Shared Yaml yaml = new Yaml()

    // -------------------------------------------------------------------------
    // Driver-to-fixture mapping table.
    //
    // Each entry:
    //   driverLabel       — human-readable name for test output
    //   ourFixtureFile    — tests/fixtures/<file>  (our fixture; has requests: section)
    //   pyFixtureFile     — tests/pyvesync-fixtures/<file>  (vendored pyvesync fixture)
    //
    // One driver may map to multiple pyvesync fixtures (e.g. the Core line where each
    // model has its own pyvesync fixture but one driver covers all four models).
    // -------------------------------------------------------------------------

    static final List<Map> DRIVER_PAIRS = [
        // ----- Purifiers: V2-API line -----
        [label: "Vital200S",       ourFile: "LAP-V201S.yaml",     pyFile: "LAP-V201S.yaml"],
        [label: "Vital100S",       ourFile: "LAP-V102S.yaml",     pyFile: "LAP-V102S.yaml"],
        [label: "SproutAir",       ourFile: "LAP-B851S-WUS.yaml", pyFile: "LAP-B851S-WUS.yaml"],
        [label: "EverestAir",      ourFile: "LAP-EL551S-WUS.yaml",pyFile: "EL551S.yaml"],

        // ----- Purifiers: Core line (old API) -----
        [label: "Core200S",        ourFile: "Core200S.yaml",       pyFile: "Core200S.yaml"],
        [label: "Core300S",        ourFile: "Core300S.yaml",       pyFile: "Core300S.yaml"],
        [label: "Core400S",        ourFile: "Core400S.yaml",       pyFile: "Core400S.yaml"],
        [label: "Core600S",        ourFile: "Core600S.yaml",       pyFile: "Core600S.yaml"],

        // ----- Humidifiers: V2-API line -----
        [label: "Superior6000S",   ourFile: "LEH-S601S.yaml",      pyFile: "LEH-S601S.yaml"],
        [label: "SproutHumidifier",ourFile: "LEH-B381S-WUS.yaml",  pyFile: "LEH-B381S.yaml"],
        [label: "OasisMist1000S",  ourFile: "LUH-M101S-WUS.yaml",  pyFile: "LUH-M101S-WUS.yaml"],
        [label: "LV600SHubConnect",ourFile: "LUH-A603S-WUS.yaml",  pyFile: "LUH-A603S-WUS.yaml"],

        // ----- Humidifiers: old API (VeSyncHumid200300S class) -----
        [label: "Classic200S",     ourFile: "Classic200S.yaml",    pyFile: "Classic200S.yaml"],
        [label: "Classic300S",     ourFile: "Classic300S.yaml",    pyFile: "Classic300S.yaml"],
        [label: "Dual200S",        ourFile: "LUH-D301S.yaml",      pyFile: "Dual200S.yaml"],
        [label: "LV600S",          ourFile: "LUH-A602S.yaml",      pyFile: "LUH-A602S-WUS.yaml"],
        [label: "OasisMist450S",   ourFile: "LUH-O451S-WUS.yaml",  pyFile: "LUH-O451S-WUS.yaml"],

        // ----- Fans -----
        [label: "TowerFan",        ourFile: "LTF-F422S.yaml",      pyFile: "LTF-F422S.yaml"],
        [label: "PedestalFan",     ourFile: "LPF-R432S.yaml",      pyFile: "LPF-R423S.yaml"],
    ]

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Load our driver fixture from tests/fixtures/<filename>.
     * Returns the Map loaded from YAML.  The fixture has a 'requests:' top-level key.
     */
    Map loadOurFixture(String filename) {
        File f = new File("tests/fixtures/${filename}")
        assert f.exists() : "Our fixture not found: tests/fixtures/${filename}"
        return yaml.load(f.text) as Map
    }

    /**
     * Load a pyvesync vendored fixture from tests/pyvesync-fixtures/<filename>.
     * Returns the Map loaded from YAML.  The fixture has operation names at the top level,
     * each with a 'json_object.payload' sub-map.
     */
    Map loadPyFixture(String filename) {
        File f = new File("tests/pyvesync-fixtures/${filename}")
        assert f.exists() : "Pyvesync vendored fixture not found: tests/pyvesync-fixtures/${filename}"
        return yaml.load(f.text) as Map
    }

    /**
     * Extract the set of operation names that appear in our fixture's 'requests:' section.
     */
    Set<String> ourOperations(Map ourFixture) {
        return (ourFixture?.requests as Map)?.keySet() ?: [] as Set
    }

    /**
     * Extract the set of operation names that appear in the pyvesync fixture.
     * Operations are top-level keys in the pyvesync fixture (filter out comments/metadata
     * keys if any).  We filter by requiring that each value is a Map containing 'json_object'.
     */
    Set<String> pyOperations(Map pyFixture) {
        return pyFixture?.findAll { k, v ->
            v instanceof Map && (v as Map).containsKey('json_object')
        }?.keySet() ?: [] as Set
    }

    /**
     * From our fixture's 'requests' map, get the payload for a given operation name.
     * Our format: requests.<op>.payload
     */
    Map ourPayload(Map ourFixture, String opName) {
        return ((ourFixture?.requests as Map)?.get(opName) as Map)?.get('payload') as Map
    }

    /**
     * From the pyvesync fixture, get the payload for a given operation name.
     * Pyvesync format: <op>.json_object.payload
     */
    Map pyPayload(Map pyFixture, String opName) {
        return (((pyFixture?.get(opName) as Map)?.get('json_object') as Map)?.get('payload')) as Map
    }

    /**
     * Build the flat list of (driverLabel, opName, ourFile, pyFile) tuples for the
     * @Unroll where: block.  Only operations present in BOTH fixtures are included.
     *
     * Each returned entry is a List: [label, opName, ourFile, pyFile]
     */
    @Shared
    List<List<Object>> coverageRows = buildCoverageRows()

    static List<List<Object>> buildCoverageRows() {
        Yaml y = new Yaml()
        List<List<Object>> rows = []

        for (Map pair in DRIVER_PAIRS) {
            String label   = pair.label as String
            String ourFile = pair.ourFile as String
            String pyFile  = pair.pyFile as String

            File ourF = new File("tests/fixtures/${ourFile}")
            File pyF  = new File("tests/pyvesync-fixtures/${pyFile}")

            if (!ourF.exists() || !pyF.exists()) {
                // Missing file: the file-existence assertion in loadOurFixture /
                // loadPyFixture will fire as a test failure when the spec runs.
                // Add a sentinel row so we get at least one test per missing pair.
                rows << [label, "__MISSING__", ourFile, pyFile]
                continue
            }

            Map ourFixture = y.load(ourF.text) as Map
            Map pyFixture  = y.load(pyF.text) as Map

            Set<String> ourOps = (ourFixture?.requests as Map)?.keySet() ?: [] as Set
            Set<String> pyOps  = pyFixture?.findAll { k, v ->
                v instanceof Map && (v as Map).containsKey('json_object')
            }?.keySet() ?: [] as Set

            Set<String> intersection = ourOps.intersect(pyOps)
            if (intersection.isEmpty()) {
                // No shared operations: add sentinel so the driver is not silently skipped.
                rows << [label, "__NO_OVERLAP__", ourFile, pyFile]
                continue
            }

            for (String op in intersection.sort()) {
                rows << [label, op, ourFile, pyFile]
            }
        }

        return rows
    }

    // -------------------------------------------------------------------------
    // Spec: one test iteration per (driver, operation) intersection pair.
    // -------------------------------------------------------------------------

    @Unroll
    def "#driverLabel: operation '#opName' — payload method and data keys match pyvesync v3.4.2"() {
        given: "both fixture files exist and have the operation"
        // Sentinel operations indicate a setup problem, not a real API mismatch.
        // Fail with a clear message rather than a null-pointer cascade.
        assert opName != "__MISSING__" :
            "${driverLabel}: fixture file missing — check tests/fixtures/${ourFile} and " +
            "tests/pyvesync-fixtures/${pyFile} both exist"
        assert opName != "__NO_OVERLAP__" :
            "${driverLabel}: no overlapping operation names between tests/fixtures/${ourFile} " +
            "and tests/pyvesync-fixtures/${pyFile} — verify the fixture files have matching " +
            "operation keys (our 'requests:' section vs pyvesync top-level operation names)"

        Map ourFixture = loadOurFixture(ourFile)
        Map pyFixture  = loadPyFixture(pyFile)

        Map ourPay = ourPayload(ourFixture, opName)
        Map pyPay  = pyPayload(pyFixture, opName)

        assert ourPay != null :
            "${driverLabel}.${opName}: our fixture has no requests.${opName}.payload — " +
            "check tests/fixtures/${ourFile}"
        assert pyPay != null :
            "${driverLabel}.${opName}: pyvesync fixture has no ${opName}.json_object.payload — " +
            "check tests/pyvesync-fixtures/${pyFile}"

        when: "we read the payload.method and payload.data key set from both fixtures"
        String ourMethod  = ourPay.get('method') as String
        String pyMethod   = pyPay.get('method')  as String
        // data may be null (empty payload for 'update' operations), treat as empty Map
        Set ourDataKeys = (ourPay.get('data') as Map)?.keySet() ?: [] as Set
        Set pyDataKeys  = (pyPay.get('data')  as Map)?.keySet() ?: [] as Set

        then: "payload.method matches pyvesync canonical"
        assert ourMethod == pyMethod :
            "${driverLabel}.${opName}: payload method mismatch\n" +
            "  ours:     '${ourMethod}'\n" +
            "  pyvesync: '${pyMethod}'\n" +
            "  Our fixture: tests/fixtures/${ourFile}\n" +
            "  Pyvesync:    tests/pyvesync-fixtures/${pyFile}"

        and: "payload.data key set matches pyvesync canonical (field names, not values)"
        assert ourDataKeys == pyDataKeys :
            "${driverLabel}.${opName}: payload.data key mismatch\n" +
            "  ours:     ${ourDataKeys.sort()}\n" +
            "  pyvesync: ${pyDataKeys.sort()}\n" +
            "  keys only in ours:     ${(ourDataKeys - pyDataKeys).sort()}\n" +
            "  keys only in pyvesync: ${(pyDataKeys - ourDataKeys).sort()}\n" +
            "  Our fixture: tests/fixtures/${ourFile}\n" +
            "  Pyvesync:    tests/pyvesync-fixtures/${pyFile}"

        where:
        [driverLabel, opName, ourFile, pyFile] << coverageRows
    }
}
