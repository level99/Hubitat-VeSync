/*
* Notify Tile Device
*
*  Licensed Virtual the Apache License, Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License. You may obtain a copy of the License at:
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
*  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
*  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
*  for the specific language governing permissions and limitations under the License.
*
*  Change History:
*
*    Date        Who            What
*    ----        ---            ----
*    2021-01-06  thebearmay	Original version 0.1.0
*    2021-01-07  thebearmay	Fix condition causing a loss notifications if they come in rapidly
*    2021-01-07  thebearmay	Add alternative date format
*    2021-01-07  thebearmay	Add last5H for horizontal display
*    2021-01-07  thebearmay	Add leading date option
*    2021-03-10  thebearmay	Lost span tag with class=last5
*    2021-11-14  ArnB  2.0.0	Add capability Momentary an routine Push allowing a Dashboard switch to clear all messages. 	
*    2021-11-15  ArnB  2.0.0	Revise logic minimizing attributes and sendevents. Allow for 5 to 20 messages in tile. Insure tile is less than 1024 	
*    2021-11-16  ArnB  2.0.1	Fix: storing one less message than requested. 
*					correct <br/> to <br />
*					Restore: attribute last5H as an optional preference. 
*    2021-11-17  ArnB  2.0.2	Add conversion logic from original version in Update routine 
*    2021-11-17  ArnB  2.0.3	Add logic when message count shinks rather than reconfigure
*    2021-11-18  ArnB  2.0.4	Add singleThreaded true
*    2021-11-18  thebearmay    2.0.5 Remove unused attributes from v1.x.x
*    2021-11-20  thebearmay    Add option to only display time
*    2021-11-22  thebearmay    make date time format a selectable option
*    2021-12-07  thebearmay    add "none" as a date time format
*    2022-04-06  thebearmay    fix max message state coming back as string
*    2022-09-15  thebearmay    issue with clean install
*    2022-12-06  thebearmay    additional date/time format
*    2026-04-29  Dan Cox       v2.4  Phase 5 — captureDiagnostics via LevoitDiagnosticsLib (no logError calls in this driver).
*    2026-04-25  Dan Cox       v2.0 (community fork, level99/Hubitat-VeSync)
*                              - Added descriptionTextEnable preference (default true) and gated logInfo helper
*/
#include level99.LevoitDiagnostics

import java.text.SimpleDateFormat
import groovy.transform.Field
static String version()	{  return '2.0.11'  }

@Field sdfList = ["ddMMMyyyy HH:mm","ddMMMyyyy HH:mm:ss","ddMMMyyyy hh:mma", "dd/MM/yyyy HH:mm:ss", "MM/dd/yyyy HH:mm:ss", "dd/MM/yyyy hh:mma", "MM/dd/yyyy hh:mma", "MM/dd HH:mm", "MM/dd h:mma", "HH:mm", "H:mm","h:mma", "None"]

metadata {
	definition (
			name: "Notification Tile",
			namespace: "thebearmay",
			description: "Simple driver to act as a destination for notifications, and provide an attribute to display the last 5 on a tile.",
			author: "Jean P. May, Jr.",
			importUrl:"https://raw.githubusercontent.com/thebearmay/hubitat/main/notifyTile.groovy",
			version: "2.4",
            singleThreaded: true
		) {
			capability "Notification"
			capability "Momentary"
            capability "Configuration"

			attribute "last5", "STRING"
			attribute "last5H", "STRING"
			attribute "last", "STRING"
			attribute "diagnostics", "string"
			command "captureDiagnostics"
			}
		}

	preferences {
		input("descriptionTextEnable", "bool", title: "Enable descriptive (info-level) logging?", defaultValue: true)
		input("debugOutput", "bool", title: "Enable debug logging?")
		input("sdfPref", "enum", title: "Date/Time Format", options:sdfList, defaultValue:"ddMMMyyyy HH:mm")
		input("leadingDate", "bool", title:"Use leading date instead of trailing")
		input("msgLimit", "number", title:"Number of messages from 5 to 20",defaultValue:5, range:5..20)
		input("create5H", "bool", title: "Create horizontal message tile?")

	}

	void installed() {
		logDebug "installed()"
		state.lastLimit=0
		configure()
	}

	void updated(){
        logDebug "updated()"
        state.driverVersion = "2.4"
        // Turn off debug log in 30 minutes (happy path — no hub reboot)
        if (settings?.debugOutput) {
            runIn(1800, "logsOff")
            state.debugEnabledAt = now()
        } else {
            state.remove("debugEnabledAt")
        }

	// V2.0.2 When converting from original version set state variables, adjust html in last5 to make it work with V2.0.0+	
		if (state?.msgCount == null)
			{
			state.lastLimit=5
			wkTile=device.currentValue("last5")
			int x = wkTile.lastIndexOf('</span>');	
			if (x>0)										//if there is anything in tile, adjust for v2.0.0
				{
				msgFilled=5
				int i = wkTile.lastIndexOf('<br /> </span>');	
				logDebug "at While i: ${i} ${msgFilled}"
				while (i>0 && msgFilled>0)
					{
					logDebug "in loop i: ${i} ${msgFilled}"
					msgFilled--
					wkTile = wkTile.substring(0, i) + '</span>'
					i = wkTile.lastIndexOf('<br /> </span>');
					logDebug "out loop i: ${i} ${msgFilled}"
					}
				logDebug "done While i: ${i} ${msgFilled}"
				sendEvent(name:"last5", value:wkTile)
				state.msgCount=msgFilled
				}
			else
				{												//process empty tile
				logDebug "Initialize an empty tile"
				state.msgCount=0
				configure()
				}
			}

        if(msgLimit == null) device.updateSetting("msgLimit",[value:5,type:"number"])
	// V2.0.3 When new msgLimit less than prior(state) msgLimit adjust message and state values	
		if (state?.lastLimit.toInteger()>settings.msgLimit.toInteger())
			{
			wkTile=device.currentValue("last5")
			msgFilled=state.msgCount.toInteger()
			logDebug "Shinking tile count lastLimit ${state.lastLimit} newLimit ${settings.msgLimit} msgCount ${msgFilled}"
			int i = wkTile.lastIndexOf('<br />');
			while (i != -1 && msgFilled > settings.msgLimit.toInteger())
				{
				wkTile = wkTile.substring(0, i) + '</span>';
				msgFilled--
				i = wkTile.lastIndexOf('<br />');
				logDebug "looping on shrink msgCount ${msgFilled}"
				}
			state.msgCount=msgFilled
			sendEvent(name:"last5", value:wkTile)
			}
		
		if (!settings.create5H)
			sendEvent(name:"last5H", value:'<span class="last5"></span>')
		state.lastLimit=settings.msgLimit	
	}

	void configure() {
		logDebug "configure()"
        if(msgLimit == null) device.updateSetting("msgLimit",[value:5,type:"number"])
		sendEvent(name:"last5", value:'<span class="last5"></span>')
		sendEvent(name:"last5H", value:'<span class="last5"></span>')
		state.msgCount=0
        if(location.hub.firmwareVersionString >= "2.2.8.0") {
            if(notify1){
                device.deleteCurrentState("notify1")
                device.deleteCurrentState("notify2")
                device.deleteCurrentState("notify3")
                device.deleteCurrentState("notify4")
                device.deleteCurrentState("notify5")
            }
        }
	}

void deviceNotification(notification){
    ensureDebugWatchdog()
	logDebug "deviceNotification entered: ${notification}"
    // One-time pref seed: heal descriptionTextEnable=true default for users migrated from older Type without Save (forward-compat)
    if (!state.prefsSeeded) {
        if (settings?.descriptionTextEnable == null) {
            device.updateSetting("descriptionTextEnable", [type:"bool", value:true])
        }
        state.prefsSeeded = true
    }
	dateNow = new Date()
    if(sdfPref == null) device.updateSetting("sdfPref",[value:"ddMMMyyyy HH:mm",type:"enum"])
    if(sdfPref != "None") {
        SimpleDateFormat sdf = new SimpleDateFormat(sdfPref)
	    if (leadingDate)
			notification = sdf.format(dateNow) + " " + notification
		else
			notification += " " + sdf.format(dateNow)
    }

	if (state?.msgCount == null)
	{
		state?.msgCount = 0
	}

	//	insert new message at beginning	of last5 string
		msgFilled = state.msgCount.toInteger()
		if (msgFilled>0)
			wkTile=device.currentValue("last5").replace('<span class="last5">','<span class="last5">' + notification + '<br />')
		else
			wkTile=device.currentValue("last5").replace('<span class="last5">','<span class="last5">' + notification)

	//	when msg count exceeds limit, purge last message
		logDebug "deviceNotification2 msgFilled: ${msgFilled} msgLimit: ${settings.msgLimit}"
		if (msgFilled < settings.msgLimit.toInteger())
			msgFilled++
		else
			{
			int i = wkTile.lastIndexOf('<br />');
			if (i != -1) 
				wkTile = wkTile.substring(0, i) + '</span>';
			}

	//	Ensure tile length is less than 1024 and hopefully stop loops
		int wkLen=wkTile.length()	
		while (wkLen > 1024 && msgFilled > 0)
			{
			logDebug "wkTile length ${wkLen}> 1024 truncating msgCount: ${msgFilled}"
			int i = wkTile.lastIndexOf('<br />');
			if (i != -1) 
				{
				wkTile = wkTile.substring(0, i) + '</span>';
				msgFilled--
				}
			else
				{
				wkTile='<span class="last5"></span>'
				msgFilled=0
				}
			wkLen=wkTile.length()
			logDebug "Truncated wkTile length ${wkLen}, msgCount: ${msgFilled}"
			}

	//	Update attributes and state
        sendEvent(name:"last", value: notification)
		sendEvent(name:"last5", value: wkTile)
		state.msgCount = msgFilled
		if (settings.create5H)
			sendEvent(name:"last5H", value: " ** "+wkTile.replaceAll("<br />"," ** ")+" ** ")
	}    

	void logsOff(){
		device.updateSetting("debugOutput",[value:"false",type:"bool"])
	}

    // BP16 debug watchdog — auto-disable stuck debugOutput after hub reboot
    // Notification Tile uses logsOff() (not logDebugOff()); watchdog mirrors that.
    // Called from top of deviceNotification() — fires on every incoming notification.
    private void ensureDebugWatchdog() {
        if (settings?.debugOutput && state.debugEnabledAt) {
            Long elapsed = now() - (state.debugEnabledAt as Long)
            if (elapsed > 30 * 60 * 1000) {
                logInfo "BP16 watchdog: 30 min elapsed since debug enable; auto-disabling now (post-reboot self-heal)"
                device.updateSetting("debugOutput", [type:"bool", value:false])
                state.remove("debugEnabledAt")
            }
        }
    }

	def logDebug(msg) {
		if (settings?.debugOutput) log.debug msg
	}

	def logInfo(msg) {
		if (settings?.descriptionTextEnable) log.info msg
	}

	void push() {
        	configure()
	}
