metadata {
    definition (name: "Samsung Metering Plug", namespace: "smartthings", author: "SmartThings") {
        capability "Energy Meter"
        capability "Power Meter"
        capability "Actuator"
        capability "Switch"
        capability "Refresh"
        capability "Health Check"
        capability "Sensor"
        capability "Configuration"
        
        command "toggle"
		command "identify"

        fingerprint profileId: "0104", inClusters: "0000, 0003, 0004, 0005, 0006, 0B04, 0702, FC82", outClusters: "0003, 000A, 0019", manufacturer: "LDS", model: "ZB-ONOFFPlug-D0000",  deviceJoinName: "Outlet" //Smart Plug
        fingerprint profileId: "0104", inClusters: "0000, 0003, 0004, 0005, 0006, 0B04, 0702, FC82", outClusters: "0003, 000A, 0019", manufacturer: "LDS", model: "ZB-ONOFFPlug-D0005",  deviceJoinName: "Outlet" //Smart Plug
	}

	preferences
	{
		section
		{
			input "enableTrace", "bool", title: "Enable trace logging?", description: "Show high-level activities during the operation of the device?", defaultValue: false, required: false, multiple: false
			input "enableDebug", "bool", title: "Enable debug logging?", description: "Show detailed responses to device commands?", defaultValue: false, required: false, multiple: false
		}
	}
}

/*
	installed
    
	Doesn't do much other than call configure().
*/
def installed()
{
	initialize()
    configure()
}


/*
	updated
    
	Doesn't do much other than call configure().
*/
def updated()
{
	initialize()
    configure()
}


/*
	initialize
    
	Doesn't do much other than call configure().
*/
def initialize()
{
	state.lastSwitch = 0

	if (enableTrace || enableDebug)
	{
		logInfo "Verbose logging has been enabled for the next 30 minutes."
		runIn(1800, logsOff)
	}
}

def parse(String description) {
	logTrace "Msg: Description is $description"

	def event = zigbee.getEvent(description)
	def msg = zigbee.parseDescriptionAsMap(description)

	logTrace "Parsed data...  Evt: ${event}, msg: ${msg}"

	// Hubitat does not seem to support power events
	if (event)
	{
		if (event.name == "power")
		{
			def value = (event.value.parseInt())
			event = createEvent(name: event.name, value: value, descriptionText: "${device.displayName} power is ${value} watts")
			logTrace "${device.displayName} power is ${value} watts"
		}
		else if (event.name == "switch")
		{
			def descriptionText = event.value == "on" ? "${device.displayName} is On" : "${device.displayName} is Off"
			event = createEvent(name: event.name, value: event.value, descriptionText: descriptionText)
			
			// Since the switch has reported that it is off it can't be using any power.  Set to zero in case the power report does not arrive, but do not report in event logs.
			if (event.value == "off") sendEvent(name: "power", value: "0", descriptionText: "${device.displayName} power is 0 watts")

			// DEVICE HEALTH MONITOR: Switch state (on/off) should report every 10mins or so, regardless of any state changes.
			// Capture the time of this message
			state.lastSwitch = now()
		}
	}
    
    	// Handle interval-based power reporting
	else if (msg?.cluster == "0B04")
	{
		// Watts
		if (msg?.attrId == "050B")
		{
			def value = Integer.parseInt(msg.value, 16)
			event = createEvent(name: "power", value: value, descriptionText: "${device.displayName} power is ${value} watts")
			logTrace "${device.displayName} power is ${value} watts"
		}
	}
    // Handle everything else
	else
	{
		def cluster = zigbee.parse(description)

		if (cluster && cluster.clusterId == 0x0006 && (cluster.command == 0x07 || cluster.command == 0x0B))
		{
			if (cluster.data[0] == 0x00 || cluster.data[0] == 0x02)
			{
				// DEVICE HEALTH MONITOR: Switch state (on/off) should report every 10mins or so, regardless of any state changes.
				// Capture the time of this message
				state.lastSwitch = now()

				if (cluster.data[0] == 0x00) logDebug "ON/OFF REPORTING CONFIG RESPONSE: " + cluster
				if (cluster.data[0] == 0x02) logDebug "ON/OFF TOGGLE RESPONSE: " + cluster
			}
			else
			{
				logError "ON/OFF REPORTING CONFIG FAILED- error code:${cluster.data[0]}"
				event = null
			}
		}
		else if (cluster && cluster.clusterId == 0x0B04 && cluster.command == 0x07)
		{
			if (cluster.data[0] == 0x00)
			{
				// Get a power meter reading
				runIn(5, "refresh")
				logDebug "POWER REPORTING CONFIG RESPONSE: " + cluster
			}
			else
			{
				logError "POWER REPORTING CONFIG FAILED- error code:${cluster.data[0]}"
				event = null
			}
		}
		else if (cluster && cluster.clusterId == 0x0003 && cluster.command == 0x04)
		{
			logInfo "LOCATING DEVICE FOR 30 SECONDS"
		}

		else
		{
			//logWarn "ash DID NOT PARSE MESSAGE for description : $description"
			//logDebug "${cluster}"
		}
	}
    return event ? createEvent(event) : event
}

/*
	on
    
	Turns the device on.

	Uses standard Zigbee on/off cluster.
*/
def on()
{
	zigbee.command(0x0006, 0x01)
}


/*
	off
    
	Turns the device off.

	Uses standard Zigbee on/off cluster.
*/
def off()
{
	zigbee.command(0x0006, 0x00)
}

/*
	toggle
    
	Toggles the device on/off state.

*/
def toggle()
{
	zigbee.command(0x0006, 0x02)
}


/*
	identify
    
	Flashes the blue LED on the plug to identify it.

*/
def identify()
{
	zigbee.writeAttribute(0x0003, 0x0000, DataType.UINT16, 0x00A)
}


/*
	refresh
    
	Refreshes the device by requesting manufacturer-specific information.

	Note: This is called from the refresh capbility
*/
def refresh()
{
	logDebug "Refresh called..."
	zigbee.onOffRefresh() + zigbee.electricMeasurementPowerRefresh()
}


/*
	configure
    
	Configures the Z-Wave repeater associations and establishes periodic device check.
*/
def configure()
{
	logDebug "Configure called..."

	// On/Off reporting of 0 seconds, maximum of 15 minutes if the device does not report any on/off activity
	zigbee.onOffConfig(0, 900) + powerConfig()
}


/*
	powerConfig
    
	Set power reporting configuration for devices with min reporting interval as 5 seconds and reporting interval if no activity as 10min (600s),
	if not otherwise specified.
*/
def powerConfig()
{
	// Calculate threshold
	def powerDelta = 1
	
	logDebug "Configuring power reporting intervals; min: ${intervalMin}, max: ${intervalMax}, delta: ${minDeltaV}, endpointId: ${endpointId}"
	
	def cfg = []

	cfg +=	zigbee.configureReporting(0x0B04, 0x050B, 0x29, 5, 600, (int) powerDelta)	// Wattage report.
	//cfg +=	zigbee.configureReporting(0x0B04, 0x0505, 0x21, 30, 900, 1)																// Voltage report
	//cfg +=	zigbee.configureReporting(0x0B04, 0x0300, 0x21, 900, 86400, 1)  														// AC Frequency Report
	
	return cfg
}
/*
	getEndpointId
    
	Helper function to get device endpoint (hex) as a String.
*/
private getEndpointId()
{
	new BigInteger(device.endpointId, 16).toString()
}


/*
	logError
    
	Displays dewarningbug output to IDE logs based on user preference.
*/
private logError(msgOut)
{
	log.error msgOut
}


/*
	logWarn
    
	Displays dewarningbug output to IDE logs based on user preference.
*/
private logWarn(msgOut)
{
		log.warn msgOut
}


/*
	logDebug
    
	Displays debug output to IDE logs based on user preference.
*/
private logDebug(msgOut)
{
	if (settings.enableDebug)
	{
		log.debug msgOut
	}
}


/*
	logTrace
    
	Displays trace output to IDE logs based on user preference.
*/
private logTrace(msgOut)
{
	if (settings.enableTrace)
	{
		log.trace msgOut
	}
}


/*
	logInfo
    
	Displays informational output to IDE logs.
*/
private logInfo(msgOut)
{
	log.info msgOut
}


/*
	logsOff
    
	Disables debug logging.
*/
def logsOff()
{
    logWarn "debug logging disabled..."
    device.updateSetting("enableTrace", [value:"false",type:"bool"])
	device.updateSetting("enableDebug", [value:"false",type:"bool"])
}

