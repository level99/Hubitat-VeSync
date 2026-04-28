package com.hubitat.app.exception

/**
 * Minimal stub for com.hubitat.app.exception.UnknownDeviceTypeException.
 *
 * The real class is provided by the Hubitat runtime sandbox. Our test classpath
 * does not include the Hubitat SDK JAR. VeSyncIntegration.groovy catches this
 * exception by fully-qualified name in safeAddChildDevice():
 *
 *   } catch (com.hubitat.app.exception.UnknownDeviceTypeException ex) { ... }
 *
 * Groovy resolves FQN catch types at compile time. Without this stub the
 * GroovyClassLoader throws "Unable to resolve class ..." before any test runs.
 *
 * The spec's addChildDevice mocks throw this exception to simulate a missing
 * driver installation. The driver's safeAddChildDevice() catch block then
 * logs an INFO message and returns null.
 */
class UnknownDeviceTypeException extends RuntimeException {
    UnknownDeviceTypeException(String driverName) {
        super("Unknown driver type: ${driverName}")
    }
}
