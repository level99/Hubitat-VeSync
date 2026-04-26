package com.hubitat.app

/**
 * Minimal stub for com.hubitat.app.ChildDeviceWrapper.
 *
 * The real class is provided by the Hubitat runtime. Our test sandbox does not
 * have it on the classpath. VeSyncIntegration.groovy uses fully-qualified type
 * annotations on local variables at lines ~384, 396, and 399:
 *
 *   List<com.hubitat.app.ChildDeviceWrapper> list = getChildDevices()
 *   com.hubitat.app.ChildDeviceWrapper equip1 = getChildDevice(device.cid)
 *   com.hubitat.app.ChildDeviceWrapper equip2 = getChildDevice(device.cid+"-nl")
 *
 * Groovy resolves FQN type annotations at compile time. Without this stub the
 * GroovyClassLoader throws "Unable to resolve class com.hubitat.app.ChildDeviceWrapper"
 * before any test runs.
 *
 * The actual runtime objects returned by getChildDevice() / addChildDevice() in the
 * VeSyncIntegrationSpec are TestDevice instances with method stubs applied via
 * metaClass. This class only needs to exist so the FQN resolves; the dynamic
 * dispatch at runtime goes to the metaClass-patched TestDevice, not here.
 *
 * Methods listed here are the ones the parent driver calls on equip1/equip2/list[i]
 * at compile-meaningful call sites (not just via dynamic dispatch). Add only what
 * is needed — do NOT add unused methods.
 */
class ChildDeviceWrapper {

    String name  = ""
    String label = ""

    /** Called on list items in the cleanup loop (line ~386). */
    String getDeviceNetworkId() { "" }

    /** Called on equip1/equip2 after add/get to persist device data (lines ~403-419). */
    void updateDataValue(String key, String value) { /* no-op in test */ }
}
