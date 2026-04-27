package support

import spock.lang.Shared
import spock.lang.Specification
import org.yaml.snakeyaml.Yaml

/**
 * Base Spock specification for Hubitat VeSync driver unit tests.
 *
 * Provides a minimal Hubitat sandbox: loads a driver .groovy source file,
 * evaluates it with metadata DSL calls neutralised, instantiates the script
 * class, and injects the mock sandbox globals (device, state, settings,
 * parent, log, sendEvent, runIn, runInMillis, pauseExecution, now,
 * location) as properties on the live instance.
 *
 * ### Driver loading strategy
 *
 * Hubitat drivers are Groovy scripts, not classes — they use top-level
 * method definitions, a `metadata { definition { ... } preferences { ... } }`
 * DSL block, and rely on sandbox-injected globals (`device`, `state`, etc.)
 * rather than explicit field declarations.
 *
 * We load them by:
 * 1. Reading the source into a String.
 * 2. Prepending a shim that defines no-op closures for the DSL keywords
 *    (`metadata`, `definition`, `capability`, `attribute`, `command`,
 *    `preferences`, `input`). This lets the metadata block evaluate
 *    without throwing MissingMethodException.
 * 3. Compiling the resulting source via GroovyClassLoader using the test
 *    classpath (so `import` statements in the driver resolve correctly).
 * 4. Instantiating the resulting class and wiring the sandbox globals via
 *    Groovy's per-instance metaClass property injection.
 *
 * ### Global injection mechanism
 *
 * Drivers access sandbox globals via bare names: `device.sendEvent(...)`,
 * `state.foo`, `settings?.bar`, `log.info ...`, `parent.sendBypassRequest(...)`.
 * In the real Hubitat sandbox these are resolved by the sandbox classloader.
 * Here, we inject them as properties on the driver instance's metaClass so
 * that Groovy's dynamic property dispatch finds them.
 *
 * For method-globals (`sendEvent`, `runIn`, `runInMillis`, `runIn`, `now`,
 * `pauseExecution`, `location`), we inject closure-valued metaClass methods
 * that delegate to spec fixtures.
 *
 * ### Fixture lifecycle
 *
 * The driver class is compiled ONCE per spec class (in `setupSpec`), amortising
 * the GroovyClassLoader overhead across all feature methods. Per-test state is
 * reset in `setup()` — device.reset(), parent.reset(), log.reset(), state.clear(),
 * settings is repopulated from `defaultSettings()`.
 *
 * ### Thread safety
 *
 * Build.gradle sets maxParallelForks = 1 — spec classes run sequentially.
 * metaClass mutations are per-instance (per driver object) so they do not
 * conflict across tests within the same spec.
 *
 * ### Fixture YAML loading
 *
 * `loadFixture(path)` parses a pyvesync YAML file from `tests/fixtures/`
 * relative to the project root and returns a Groovy Map. Subclasses call
 * this in `given:` blocks to build `applyStatus` input.
 */
abstract class HubitatSpec extends Specification {

    // Stable per-test fixtures — reset in setup()
    protected TestDevice testDevice
    protected TestParent testParent
    protected TestLog    testLog
    protected Map        state    = [:]
    protected Map        settings = [:]

    // Compiled driver class — loaded once per spec class in setupSpec().
    // @Shared is required: setupSpec() runs in a static-like context; instance fields
    // are not accessible there. @Shared fields ARE accessible and persist across
    // all feature methods (correct lifecycle — the parsed Class doesn't change).
    @Shared protected Class driverClass

    // Instantiated driver per test — wired in setup()
    protected def driver

    // -------------------------------------------------------------------------
    // Subclass contract
    // -------------------------------------------------------------------------

    /**
     * Return the absolute path to the .groovy driver source file.
     * Example: new File("Drivers/Levoit/LevoitVital200S.groovy").absolutePath
     */
    abstract String driverSourcePath()

    /**
     * Return the default settings map for this driver.
     * Override to pre-populate `descriptionTextEnable`, `debugOutput`, etc.
     * The base implementation returns a map with both prefs null (as-if not
     * yet saved by user — triggers the pref-seed path in drivers that have it).
     */
    Map defaultSettings() {
        return [:]
    }

    // -------------------------------------------------------------------------
    // setupSpec / setup
    // -------------------------------------------------------------------------

    /**
     * Subclasses must call super.setupSpec() OR call loadDriverClass() themselves.
     * Compiling once per spec class keeps the test suite fast.
     */
    def setupSpec() {
        driverClass = loadDriverClass(driverSourcePath())
    }

    def setup() {
        testDevice = new TestDevice()
        testParent = new TestParent()
        testLog    = new TestLog()
        state      = [:]
        settings   = new HashMap(defaultSettings())

        // Wire testDevice's settingsRef so updateSetting() reflects into
        // the settings map that the driver reads.
        testDevice.settingsRef = settings

        // Instantiate a fresh driver object per test
        driver = driverClass.getDeclaredConstructor().newInstance()

        // Inject sandbox globals
        wireSandbox(driver)
    }

    // -------------------------------------------------------------------------
    // Driver loading
    // -------------------------------------------------------------------------

    /**
     * Load and compile the driver source with metadata DSL shims prepended.
     * Returns the compiled Class. Called once per spec (setupSpec).
     */
    protected Class loadDriverClass(String sourcePath) {
        def file = new File(sourcePath)
        if (!file.exists()) {
            throw new FileNotFoundException(
                "Driver source not found: ${sourcePath}\n" +
                "Working dir: ${new File('.').absolutePath}")
        }
        def source = file.text

        // Prepend metadata DSL shims. These define no-op closures for the
        // keywords Hubitat evaluates at definition-time. Without these, the
        // `metadata { definition(...) { capability "..." } }` block throws
        // MissingMethodException when the script body is first evaluated.
        //
        // The shims are defined as methods (not closures) so they match
        // both `metadata { ... }` (method call with closure arg) and
        // `metadata { definition(...) { ... } }` (nested closures).
        // `capability`, `attribute`, `command`, `input` are defined to accept
        // varargs so they work regardless of how many args the driver passes.
        def shim = '''\
def metadata(Closure c)   { /* no-op */  }
def definition(Map m)     { /* no-op */  }
def definition(Map m, Closure c) { c?.call() }
def capability(String s)  { /* no-op */  }
def attribute(Object... a){ /* no-op */  }
def command(Object... a)  { /* no-op */  }
def preferences(Closure c){ c?.call()   }
def input(Object... a)    { /* no-op */  }
// @Field and import declarations from driver source are fine as-is.
// `static String version()` (Notification Tile) is also fine.
'''
        def shimmedSource = shim + "\n" + source

        def gcl = new GroovyClassLoader(this.class.classLoader)
        try {
            return gcl.parseClass(shimmedSource, file.name)
        } catch (Exception e) {
            throw new RuntimeException(
                "Failed to parse driver source ${sourcePath}: ${e.message}", e)
        }
    }

    // -------------------------------------------------------------------------
    // Sandbox wiring
    // -------------------------------------------------------------------------

    /**
     * Wire all sandbox globals onto a freshly-instantiated driver object.
     * Called once per test from setup().
     *
     * The driver accesses globals via Groovy property/method lookup. We inject
     * them on the per-instance metaClass so the driver's own method bodies
     * see them without any source modification.
     */
    protected void wireSandbox(def driverInstance) {
        def mc = driverInstance.metaClass

        // --- device ---
        // Drivers use `device.sendEvent(...)`, `device.currentValue(...)`, etc.
        // Injected as a property returning the TestDevice instance.
        mc.getDevice = { -> testDevice }

        // --- state ---
        // Drivers use `state.foo`, `state.foo = bar`, `state.remove('foo')`.
        // We inject a Map with MOP. Because state is a plain Map, we store it
        // as a property that returns our live `state` map reference, and Groovy
        // routes map subscript/property access to it automatically.
        mc.getState = { -> state }

        // --- settings ---
        // Drivers use `settings?.foo`. We return the live `settings` map.
        mc.getSettings = { -> settings }

        // --- parent ---
        mc.getParent = { -> testParent }

        // --- log ---
        mc.getLog = { -> testLog }

        // --- sendEvent (top-level, no `device.` prefix) ---
        // Some drivers call `sendEvent(name: "level", value: val)` directly
        // (without `device.`). Forward to device.sendEvent.
        mc.sendEvent = { Map args -> testDevice.sendEvent(args) }

        // --- runIn ---
        mc.runIn = { int delay, String methodName ->
            // Record for assertions; don't actually schedule.
            // Specs can verify via testParent.allRequests or by asserting
            // no side-effects occurred.
        }

        // --- runInMillis ---
        mc.runInMillis = { int delay, String methodName ->
            // no-op: don't schedule real timer
        }

        // --- unschedule (0-arg: clear all schedules; 1-arg: clear named method) ---
        // NOTE: Groovy ExpandoMetaClass does not support true method overloading via property
        // assignment (mc.foo = { ... } replaces any prior assignment of the same name).
        // Both the 0-arg form (unschedule()) and the 1-arg form (unschedule("method"))
        // must work, so we use a single varargs-accepting closure that handles both.
        mc.unschedule = { Object[] args ->
            // no-op: both `unschedule()` (args.length==0) and `unschedule("updateDevices")`
            // (args.length==1) are handled here. No actual timer cancellation in unit tests.
        }

        // --- schedule (Hubitat cron-based recurring timer, persists across reboots) ---
        // Used by setupPollSchedule() (Bug Pattern #14 fix).
        // No-op base implementation; VeSyncIntegrationSpec overrides this per-test where needed.
        mc.schedule = { String cronExpression, String handlerMethodName ->
            // no-op: don't schedule real timers in unit tests
        }

        // ---------------------------------------------------------------------------
        // App-only APIs -- FAIL FAST in driver context (Bug Pattern #15)
        //
        // subscribe(location, ...) and unsubscribe() are only available to Hubitat
        // *apps* (SmartApps / installed-app sandboxes). They are NOT available to
        // drivers. The Hubitat platform throws MissingMethodException at runtime if
        // driver code calls them. These mocks replicate that production behavior so
        // tests catch driver code that accidentally uses app-only APIs.
        //
        // If you see a test fail here, the driver code (not the test) is wrong.
        // ---------------------------------------------------------------------------
        mc.subscribe = { Object[] args ->
            throw new MissingMethodException("subscribe", this.class, args, false)
        }
        mc.unsubscribe = { Object[] args ->
            throw new MissingMethodException("unsubscribe", this.class, args, false)
        }

        // --- pauseExecution ---
        mc.pauseExecution = { int ms ->
            // no-op: no real sleep in unit tests
        }

        // --- now() ---
        mc.now = { -> 1745000000000L }  // fixed epoch ms for deterministic tests

        // --- location ---
        // Superior 6000S uses `location?.temperatureScale`.
        mc.getLocation = { ->
            [temperatureScale: "F"] as Object
        }

        // --- httpPost ---
        // Parent driver uses this for login/getDevices. Not used by children.
        // Children call parent.sendBypassRequest instead.
        mc.httpPost = { Map params, Closure callback ->
            throw new UnsupportedOperationException(
                "httpPost called unexpectedly in child driver spec. " +
                "Child drivers should call parent.sendBypassRequest instead.")
        }

        // --- asynchttpPost ---
        mc.asynchttpPost = { String callbackMethod, Map params, Map data = [:] ->
            throw new UnsupportedOperationException(
                "asynchttpPost called unexpectedly in child driver spec.")
        }
    }

    // -------------------------------------------------------------------------
    // Fixture loading
    // -------------------------------------------------------------------------

    /**
     * Parse a pyvesync YAML fixture file and return a Groovy Map.
     *
     * @param relativePath path relative to tests/fixtures/, e.g. "LAP-V201S.yaml"
     * @return parsed Map from SnakeYAML
     */
    protected Map loadYamlFixture(String relativePath) {
        def file = new File("tests/fixtures/${relativePath}")
        if (!file.exists()) {
            throw new FileNotFoundException(
                "Fixture file not found: tests/fixtures/${relativePath}\n" +
                "Working dir: ${new File('.').absolutePath}")
        }
        def yaml = new Yaml()
        return yaml.load(file.text) as Map
    }

    /**
     * Build a single-wrapped status response map (purifier shape).
     * Simulates what the parent driver passes to child.update() after a getPurifierStatus call.
     *
     * The parent calls `resp.data.result` and passes that to `dev.update(status, nightLight)`.
     * So `status` is `resp.data.result`, which for a purifier is:
     *   { code: 0, result: { <actual device fields> }, traceId: ... }
     *
     * The child's `applyStatus` then does `r = status?.result` to get device fields.
     * This helper wraps deviceData in that single envelope layer.
     *
     * NOTE: This is NOT the double-wrapped humidifier shape. For that, use humidifierStatusEnvelope().
     */
    protected Map purifierStatusEnvelope(Map deviceData) {
        return [
            code: 0,
            result: deviceData,
            traceId: "test-trace"
        ]
    }

    /**
     * Build a double-wrapped status response map (humidifier shape).
     * Superior 6000S: parent passes resp.data.result which is:
     *   { code: 0, result: { code: 0, result: { <device fields> } } }
     * The peel-while-loop in applyStatus peels twice to reach device fields.
     */
    protected Map humidifierStatusEnvelope(Map deviceData) {
        return [
            code: 0,
            result: [
                code: 0,
                result: deviceData,
                traceId: "test-trace-inner"
            ],
            traceId: "test-trace"
        ]
    }

    // -------------------------------------------------------------------------
    // Assertion helpers
    // -------------------------------------------------------------------------

    /** True if testDevice received a sendEvent with the given name and value. */
    protected boolean eventEmitted(String name, Object value) {
        testDevice.events.any { it.name == name && it.value == value }
    }

    /** The most recent value emitted for the named attribute. Null if none. */
    protected Object lastEventValue(String name) {
        testDevice.lastEvent(name)?.value
    }

    /** Assert that no log.error calls were made. Useful as a sanity check. */
    protected void assertNoErrors() {
        assert testDevice.events || true  // just a hook; real assertion below
        assert testLog.errors.isEmpty() :
            "Expected no log.error calls, but got: ${testLog.errors}"
    }
}
