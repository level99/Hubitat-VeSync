/*
 * MIT License
 *
 * Copyright (c) 2026 Dan Cox (level99/Hubitat-VeSync community fork)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND.
 */

/*
 *  LevoitDiagnostics — Hubitat library
 *
 *  Shared captureDiagnostics() + error ring-buffer helpers for all Levoit drivers
 *  (community fork v2.4+).
 *
 *  Exports:
 *    captureDiagnostics()                               — command entrypoint; builds & stores markdown dump
 *    recordError(String msg, Map ctx, String overrideDni) — ring-buffer recorder (last 10 per device, FIFO);
 *                                                           overrideDni allows parent to record under a child DNI
 *    buildIssueUrl(Map fields)                          — assembles pre-filled GitHub issue URL
 *    buildDiagnosticBlock(Map opts)                     — assembles the markdown dump
 *
 *  GitHub issue template field IDs (these MUST match .github/ISSUE_TEMPLATE/bug_report.yml):
 *    driver-name, driver-version, model-code, hub-firmware, last-error, diagnostic-block
 *
 *  URL truncation: diagnostic-block is trimmed when total URL > 7500 chars to stay
 *  under browser/GitHub practical limits (~8KB). A truncation hint is appended.
 *
 *  Project: https://github.com/level99/Hubitat-VeSync
 */

library(
    name: "LevoitDiagnostics",
    namespace: "level99",
    author: "Dan Cox",
    description: "Shared captureDiagnostics() + error ring-buffer helpers for Levoit drivers (community fork v2.4+).",
    importUrl: "https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/Drivers/Levoit/LevoitDiagnosticsLib.groovy",
    documentationLink: "https://github.com/level99/Hubitat-VeSync/blob/main/CONTRIBUTING.md"
)

// ---------------------------------------------------------------------------
// Ring-buffer error recorder
//
// Appends {ts, msg, ctx} to state.errorHistory[<dni>].
// Bounded at 10 entries; oldest is FIFO-evicted when the list is full.
// Initializes the slot lazily (no NPE on first call).
//
// state is a JSON-backed proxy in Hubitat — map-slot writes must use whole-map
// reassignment to persist (same discipline as BP17 consecutiveEmpty counter).
//
// Privacy: caller is responsible for not passing raw credentials. Messages
// routed through logError() (which itself routes through sanitize() on the
// parent) are safe because sanitize() strips email/token/accountID.
// ---------------------------------------------------------------------------

/**
 * Append one error entry to the ring buffer for this device.
 *
 * @param msg         Error message string (sanitized by caller before passing).
 * @param ctx         Optional Map of additional context fields (e.g. [method:"getPurifierStatus"]).
 * @param overrideDni When non-null, store the entry under this DNI instead of the
 *                    calling device's own DNI. Used by the parent driver when logging
 *                    a per-child failure (e.g. "No status returned for <childDni>") so
 *                    that captureDiagnosticsFor(childDni) finds the error in the child's
 *                    slot rather than the parent's slot.
 */
void recordError(String msg, Map ctx = [:], String overrideDni = null) {
    if (device == null) return   // defensive — should never happen in driver context

    String dni
    if (overrideDni) {
        dni = overrideDni
    } else {
        try { dni = device.deviceNetworkId } catch (ignored) { return }
    }
    if (!dni) return

    Map history = (state.errorHistory ?: [:]) as Map
    List slot   = (history[dni] ?: []) as List

    Map entry = [ts: now(), msg: (msg ?: "").take(500), ctx: ctx ?: [:]]
    slot << entry

    // FIFO eviction: keep only the last 10 entries
    if (slot.size() > 10) slot = slot.drop(slot.size() - 10)

    history[dni] = slot
    state.errorHistory = history
}

// ---------------------------------------------------------------------------
// captureDiagnostics — command entrypoint
//
// For child drivers: calls parent.captureDiagnosticsFor(dni) to get parent-side
// context (last poll method, consecutiveEmpty counter, configModule, last error).
// For the parent driver itself: builds its own state dump directly.
//
// Stores result as a sendEvent for the "diagnostics" attribute (string).
// Logs [DIAG] completion at INFO so users can see it happened without debug on.
// ---------------------------------------------------------------------------

/**
 * Build, store, and log a diagnostics snapshot for this device.
 * Called as a Hubitat command from the device page.
 */
void captureDiagnostics() {
    logDebug "captureDiagnostics()"

    // Determine driver metadata
    String driverName    = device?.typeName ?: device?.name ?: "Unknown Driver"
    String driverVersion = getDriverVersion()
    String dni           = device?.deviceNetworkId ?: "?"
    String modelCode     = getModelCode()
    String hubFw         = getHubFirmware()

    // Gather parent-side context (children only — parent implements captureDiagnosticsFor itself)
    Map parentCtx = [:]
    try {
        if (parent?.respondsTo("captureDiagnosticsFor")) {
            parentCtx = parent.captureDiagnosticsFor(dni) ?: [:]
        }
    } catch (ignored) {}

    // Error history for this device
    List errors = getErrorHistory(dni)

    // Current attribute snapshot
    Map attrSnap = captureAttributeSnapshot()

    // Assemble block
    Map opts = [
        driverName:    driverName,
        driverVersion: driverVersion,
        modelCode:     modelCode,
        hubFw:         hubFw,
        dni:           dni,
        parentCtx:     parentCtx,
        errors:        errors,
        attrSnap:      attrSnap
    ]
    String block = buildDiagnosticBlock(opts)

    device.sendEvent(name: "diagnostics", value: block)
    logInfo "[DIAG] captureDiagnostics complete — see diagnostics attribute (${driverName} v${driverVersion})"
}

// ---------------------------------------------------------------------------
// buildDiagnosticBlock — assemble the markdown dump
// ---------------------------------------------------------------------------

/**
 * Assemble the full markdown diagnostic block.
 *
 * @param opts Map containing: driverName, driverVersion, modelCode, hubFw, dni,
 *             parentCtx (Map), errors (List), attrSnap (Map)
 * @return     Markdown string suitable for the diagnostics attribute and the GitHub issue body.
 */
String buildDiagnosticBlock(Map opts) {
    String driverName    = opts.driverName    ?: "Unknown"
    String driverVersion = opts.driverVersion ?: "?"
    String modelCode     = opts.modelCode     ?: "UNKNOWN"
    String hubFw         = opts.hubFw         ?: "?"
    String dni           = opts.dni           ?: "?"
    Map    parentCtx     = (opts.parentCtx    ?: [:]) as Map
    List   errors        = (opts.errors       ?: [])  as List
    Map    attrSnap      = (opts.attrSnap     ?: [:])  as Map

    def sb = new StringBuilder()
    sb.append("### Levoit Driver Diagnostics\n\n")

    // --- Driver / device metadata ---
    sb.append("#### Driver & Device\n\n")
    sb.append("| Field | Value |\n")
    sb.append("|---|---|\n")
    sb.append("| Driver | `${driverName}` |\n")
    sb.append("| Driver version | `${driverVersion}` |\n")
    sb.append("| Model code | `${modelCode}` |\n")
    sb.append("| Hub firmware | `${hubFw}` |\n")
    sb.append("| Device Network ID | `${dni}` |\n")
    sb.append("\n")

    // --- Parent-side state ---
    sb.append("#### Parent state for this device\n\n")
    if (parentCtx) {
        sb.append("| Key | Value |\n")
        sb.append("|---|---|\n")
        parentCtx.each { k, v -> sb.append("| ${k} | `${v}` |\n") }
    } else {
        sb.append("_(not available — parent not accessible or does not support captureDiagnosticsFor)_\n")
    }
    sb.append("\n")

    // --- Error history ---
    sb.append("#### Recent errors (last ${errors.size()}, max 10)\n\n")
    if (errors) {
        sb.append("| Timestamp | Message | Context |\n")
        sb.append("|---|---|---|\n")
        errors.each { e ->
            String ts  = e.ts  ? new Date(e.ts as Long).format("yyyy-MM-dd HH:mm:ss") : "?"
            String msg = (e.msg ?: "").take(200)
            String ctx = e.ctx ? e.ctx.toString().take(100) : ""
            sb.append("| ${ts} | ${msg} | ${ctx} |\n")
        }
    } else {
        sb.append("_(no errors recorded)_\n")
    }
    sb.append("\n")

    // --- Attribute snapshot ---
    sb.append("#### Current attribute snapshot\n\n")
    if (attrSnap) {
        sb.append("| Attribute | Value |\n")
        sb.append("|---|---|\n")
        attrSnap.each { k, v -> sb.append("| ${k} | `${v}` |\n") }
    } else {
        sb.append("_(no attributes captured)_\n")
    }
    sb.append("\n")

    // --- Footer with clickable issue URL ---
    String lastError = errors ? (errors[-1]?.msg ?: "") as String : ""
    String issueUrl = buildIssueUrl([
        "driver-name":        driverName,
        "driver-version":     driverVersion,
        "model-code":         modelCode,
        "hub-firmware":       hubFw,
        "last-error":         lastError,
        "diagnostic-block":   sb.toString()  // will be truncated inside if needed
    ])

    sb.append("---\n")
    sb.append("**[Click here to open a pre-filled GitHub issue](${issueUrl})**\n\n")
    sb.append("_Or paste this entire block into the diagnostic-block field of a [new bug report]")
    sb.append("(https://github.com/level99/Hubitat-VeSync/issues/new?template=bug_report.yml)._\n")

    return sb.toString()
}

// ---------------------------------------------------------------------------
// buildIssueUrl — URL-encode fields into a GitHub new-issue pre-fill URL
//
// Field IDs MUST match .github/ISSUE_TEMPLATE/bug_report.yml body element IDs.
// Current mapping:
//   driver-name       → id: driver-name
//   driver-version    → id: driver-version
//   model-code        → id: model-code
//   hub-firmware      → id: hub-firmware
//   last-error        → id: last-error
//   diagnostic-block  → id: diagnostic-block
//
// Truncation: when the total URL would exceed 7500 chars, diagnostic-block is
// trimmed. The truncated URL appends a hint so the user knows to paste the full
// block manually from the diagnostics attribute.
// ---------------------------------------------------------------------------

/**
 * Build a GitHub issues pre-fill URL from the given fields map.
 * Keys must match the YAML issue template field IDs (see comment above).
 *
 * @param fields  Map of field-id → field-value strings.
 * @return        Pre-filled GitHub new-issue URL (string).
 */
String buildIssueUrl(Map fields) {
    String base = "https://github.com/level99/Hubitat-VeSync/issues/new"

    // Derive labels from driver name (best-effort family detection)
    String driverName = (fields["driver-name"] ?: "") as String
    String family = "driver"
    if (driverName.toLowerCase().contains("humidifier")) family = "humidifier"
    else if (driverName.toLowerCase().contains("fan"))   family = "fan"
    else if (driverName.toLowerCase().contains("purifier") || driverName.toLowerCase().contains("air")) family = "purifier"

    // Build title
    String modelCode  = (fields["model-code"] ?: "") as String
    String lastError  = (fields["last-error"]  ?: "") as String
    String titleSuffix = lastError ? lastError.take(60) : (modelCode ?: driverName.take(40))
    String title = "[${driverName.take(30)}] ${titleSuffix}"

    // Build param map (without diagnostic-block first — we measure that separately)
    Map params = [
        "template":       "bug_report.yml",
        "labels":         "diag-prefilled,bug,${family}",
        "title":          title,
        "driver-name":    fields["driver-name"]    ?: "",
        "driver-version": fields["driver-version"] ?: "",
        "model-code":     fields["model-code"]     ?: "",
        "hub-firmware":   fields["hub-firmware"]   ?: "",
        "last-error":     fields["last-error"]     ?: ""
    ]

    // URL-encode everything except diagnostic-block for length measurement
    String baseParams = params.collect { k, v ->
        "${urlEncode(k)}=${urlEncode(v as String)}"
    }.join("&")

    String diagRaw = (fields["diagnostic-block"] ?: "") as String
    int budgetForDiag = 7500 - base.length() - 1 - baseParams.length() - "&diagnostic-block=".length()

    String diagEncoded
    if (budgetForDiag <= 0) {
        // No room at all — skip diagnostic-block
        diagEncoded = urlEncode("[diagnostic too long for URL — paste full block from diagnostics attribute]")
    } else {
        String encoded = urlEncode(diagRaw)
        if (encoded.length() <= budgetForDiag) {
            diagEncoded = encoded
        } else {
            // Binary-search the right truncation point (encoded length is not linear in raw chars)
            // Simple approach: trim raw string iteratively. URL-encode overhead ~3x worst case.
            // Start at budget / 3 raw chars as lower bound, walk up.
            int rawLen = Math.min(diagRaw.length(), (int)(budgetForDiag / 3))
            while (rawLen > 0 && urlEncode(diagRaw.take(rawLen)).length() > budgetForDiag - 80) {
                rawLen = (int)(rawLen * 0.9)
            }
            String hint = "\n…[diagnostic too long for URL — paste full block manually from diagnostics attribute]"
            diagEncoded = urlEncode(diagRaw.take(rawLen) + hint)
        }
    }

    return "${base}?${baseParams}&${urlEncode('diagnostic-block')}=${diagEncoded}"
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

/**
 * URL-encode a string for use in query parameters.
 * Uses percent-encoding; spaces become %20 (not +) per RFC 3986.
 */
private String urlEncode(String s) {
    if (!s) return ""
    try {
        return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    } catch (ignored) {
        return s
    }
}

/**
 * Return the error history list for the given DNI from state.errorHistory.
 * Returns an empty list if no history exists.
 */
private List getErrorHistory(String dni) {
    if (!dni) return []
    Map history = (state.errorHistory ?: [:]) as Map
    List slot = (history[dni] ?: []) as List
    return slot
}

/**
 * Read the driver version from the device metadata, then from the
 * driver's version field if available.
 * Falls back to "2.3" (current codebase default).
 */
private String getDriverVersion() {
    try {
        // Hubitat exposes device.getTypeName() — the driver name includes version in metadata.
        // The cleanest path for a library is to look for a DRIVER_VERSION constant or
        // fall back to the manifest version. Here we use a well-known fallback.
        // Drivers that want to expose version can set state.driverVersion in updated().
        if (state.driverVersion) return state.driverVersion as String
    } catch (ignored) {}
    return "2.3"
}

/**
 * Get the model code for this device.
 * Reads from device.getDataValue("deviceType"), the VeSync raw model code.
 */
private String getModelCode() {
    try { return device?.getDataValue("deviceType") ?: "UNKNOWN" } catch (ignored) { return "UNKNOWN" }
}

/**
 * Get the hub firmware version.
 * Uses location.hsmStatus on older firmware; falls back to a best-effort string.
 */
private String getHubFirmware() {
    try {
        // location.hsmStatus is not firmware — try the hub version string via location
        // Hubitat exposes location.hub.firmwareVersionString on 2.3.x
        def fw = location?.hub?.firmwareVersionString
        if (fw) return fw as String
    } catch (ignored) {}
    try {
        def fw = location?.hub?.data?.get("fullVersion")
        if (fw) return fw as String
    } catch (ignored) {}
    return "unknown"
}

/**
 * Capture a snapshot of current attribute values.
 * Uses device.currentStates() — available on all Hubitat devices.
 * Returns a Map of name -> value (most recent value per attribute).
 */
private Map captureAttributeSnapshot() {
    Map snap = [:]
    try {
        def states = device?.currentStates
        states?.each { s ->
            if (s.name && s.value != null) {
                // Skip the diagnostics attribute itself — avoid embedding the old block in the new one
                if (s.name != "diagnostics") {
                    snap[s.name] = s.value
                }
            }
        }
    } catch (ignored) {}
    return snap
}
