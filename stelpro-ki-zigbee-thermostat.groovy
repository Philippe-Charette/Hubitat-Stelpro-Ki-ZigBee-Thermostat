/**
 *  Copyright 2019 Philippe Charette
 *  Copyright 2017 Stelpro
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Stelpro Ki ZigBee Thermostat Hubitat Driver
 *
 *  Notice: This file is a modified version of the SmartThings Device Hander, found in this repository:
 *           https://github.com/stelpro/Ki-ZigBee-Thermostat
 *
 *  Author: Philippe Charette
 *  Author: Stelpro
 *
 *  Date: 2019-08-05
 */

metadata {
	definition (name: "Stelpro Ki ZigBee Thermostat", namespace: "PhilC", author: "PhilC") {
        capability "Configuration"
        capability "TemperatureMeasurement"
        capability "ThermostatHeatingSetpoint"
        capability "ThermostatMode"
        capability "ThermostatOperatingState"
        capability "Refresh"
        
        command "eco"

		fingerprint profileId: "0104", endpointId: "19", inClusters: " 0000,0003,0201,0204", outClusters: "0402"
    }
    
    preferences {
        input("lock", "enum", title: "Do you want to lock your thermostat's physical keypad?", options: ["No", "Yes"], defaultValue: "No", required: false, displayDuringSetup: false)
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}
    

def parse(String description) {
	logDebug "Parse description $description"
    def descMap = zigbee.parseDescriptionAsMap(description)
    logDebug "Desc Map: $descMap"
	def map = [:]
	if (description?.startsWith("read attr -")) {
		if (descMap.cluster == "0201" && descMap.attrId == "0000")
        {
			map.name = "temperature"
			map.value = getTemperature(descMap.value)
            if (descMap.value == "7FFD") {		//0x7FFD
                map.value = "low"
            }
            else if (descMap.value == "7FFF") {	//0x7FFF
                map.value = "high"
            }
            else if (descMap.value == "8000") {	//0x8000
                map.value = "--"
            }
            
            else if (descMap.value > "8000") {
                map.value = -(Math.round(2*(655.36 - map.value))/2)
            }
                        
            sendEvent(name:"temperature", value:map.value)
		}
        else if (descMap.cluster == "0201" && descMap.attrId == "0012") {
			logDebug "HEATING SETPOINT"
			map.name = "heatingSetpoint"
			map.value = getTemperature(descMap.value)
            if (descMap.value == "8000") {		//0x8000
                map.value = "--"
            }
            sendEvent(name:"heatingSetpoint", value:map.value)
		}
        else if (descMap.cluster == "0201" && descMap.attrId == "001C") {
            logDebug "MODE"
            if (descMap.value != "04") {
                map.name = "thermostatMode"
                map.value = getModeMap()[descMap.value]
                sendEvent(name:"thermostatMode", value:map.value)          
            }
            else {
                logDebug "descMap.value == \"04\". Ignore and wait for SETPOINT MODE"
            }
		}
        else if (descMap.cluster == "0201" && descMap.attrId == "401C") {
            logDebug "SETPOINT MODE"
            logDebug "descMap.value $descMap.value"
            if (descMap.value != "00") {
                map.name = "thermostatMode"
                map.value = getModeMap()[descMap.value]
                sendEvent(name:"thermostatMode", value:map.value)
            }
            else {
                logDebug "descMap.value == \"00\". Ignore and wait for MODE"
            }
		}
        else if (descMap.cluster == "0201" && descMap.attrId == "0008") {
        	logDebug "HEAT DEMAND"
            map.name = "thermostatOperatingState"
            map.value = getModeMap()[descMap.value]
            if (descMap.value < "10") {
            	map.value = "idle"
            }
            else {
            	map.value = "heating"
            }
            sendEvent(name:"thermostatOperatingState", value:map.value)
        }
	}

	def result = null
	if (map) {
		result = createEvent(map)
	}
	logDebug "Parse returned $map"
	return result
}

def logsOff(){
    log.warn "debug logging disabled..."
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}

def refresh() {
    logDebug "refresh"
    def cmds = []
    
    cmds += zigbee.readAttribute(0x201, 0x0000) //Read Local Temperature
    cmds += zigbee.readAttribute(0x201, 0x0008) //Read PI Heating State  
    cmds += zigbee.readAttribute(0x201, 0x0012) //Read Heat Setpoint
    cmds += zigbee.readAttribute(0x201, 0x001C) //Read System Mode
    cmds += zigbee.readAttribute(0x201, 0x401C, [mfgCode: "0x1185"]) //Read System Mode
    
    cmds += zigbee.readAttribute(0x204, 0x0000) //Read Temperature Display Mode
    cmds += zigbee.readAttribute(0x204, 0x0001) //Read Keypad Lockout
    
    return cmds
}    

def logDebug(value){
    if (logEnable) log.debug(value)
}

def configure(){    
    log.warn "configure..."
    runIn(1800,logsOff)    
	logDebug "binding to Thermostat cluster"
    
    def cmds = [
    //bindings
        "zdo bind 0x${device.deviceNetworkId} 1 0x019 0x201 {${device.zigbeeId}} {}", "delay 200"
        ]
    
    //reporting
    cmds += zigbee.configureReporting(0x201, 0x0000, 0x29, 10, 60, 50)   //Attribute ID 0x0000 = local temperature, Data Type: S16BIT
    cmds += zigbee.configureReporting(0x201, 0x0008, 0x20, 10, 900, 5)   //Attribute ID 0x0008 = pi heating demand, Data Type: U8BIT
    cmds += zigbee.configureReporting(0x201, 0x0012, 0x29, 1, 0, 50)     //Attribute ID 0x0012 = occupied heat setpoint, Data Type: S16BIT
    cmds += zigbee.configureReporting(0x201, 0x001C, 0x30, 1, 0, 1)      //Attribute ID 0x001C = system mode, Data Type: 8 bits enum
    cmds += zigbee.configureReporting(0x201, 0x401C, 0x30, 1, 0, 1, [mfgCode: "0x1185"])   	   //Attribute ID 0x401C = manufacturer specific setpoint mode, Data Type: 8 bits enum
    
    cmds += zigbee.configureReporting(0x204, 0x0000, 0x30, 1, 0)   	  //Attribute ID 0x0000 = temperature display mode, Data Type: 8 bits enum
    cmds += zigbee.configureReporting(0x204, 0x0001, 0x30, 1, 0)   	  //Attribute ID 0x0001 = keypad lockout, Data Type: 8 bits enum
    
    return cmds + refresh()
}

def getModeMap() { [
	"00":"off",
    "04":"heat",
	"05":"eco"
]}

def getFanModeMap() { [
	"04":"fanOn",
	"05":"fanAuto"
]}

def getTemperature(value) {
	if (value != null) {
    	logDebug("value $value")
		def celsius = Integer.parseInt(value, 16) / 100
		if (getTemperatureScale() == "C") {
			return celsius
		}
        else {
			return Math.round(celsiusToFahrenheit(celsius))
		}
	}
}

def off() {
	logDebug "off"
    zigbee.writeAttribute(0x201, 0x001C, 0x30, 0)
}

def heat() {
	logDebug "heat"
    
    def cmds = []
    cmds += zigbee.writeAttribute(0x201, 0x001C, 0x30, 04, [:], 1000) // MODE
    cmds += zigbee.writeAttribute(0x201, 0x401C, 0x30, 04, [mfgCode: "0x1185"]) // SETPOINT MODE    
    return cmds
}

def eco() {
	logDebug "eco"
    
    def cmds = []
    cmds += zigbee.writeAttribute(0x201, 0x001C, 0x30, 04, [:], 1000) // MODE
    cmds += zigbee.writeAttribute(0x201, 0x401C, 0x30, 05, [mfgCode: "0x1185"]) // SETPOINT MODE    
    return cmds
}

def cool() {
	logDebug "cool unavailable calling eco"
    eco()
}

def emergencyHeat() {
	logDebug "emergencyHeat unavailable calling heat"
	heat()
}

def setHeatingSetpoint(preciseDegrees) {
	if (preciseDegrees != null) {
		def temperatureScale = getTemperatureScale()
		def degrees = new BigDecimal(preciseDegrees).setScale(1, BigDecimal.ROUND_HALF_UP)
        
		logDebug "setHeatingSetpoint(${degrees} ${temperatureScale})"
        
        def celsius = (getTemperatureScale() == "C") ? degrees as Float : (fahrenheitToCelsius(degrees) as Float).round(2)
        int celsius100 = Math.round(celsius * 100)
        
        zigbee.writeAttribute(0x201, 0x0012, 0x29, celsius100) //Write Heat Setpoint      
	}
}

def setThermostatMode(String value) {
	logDebug "setThermostatMode({$value})"
	def currentMode = device.currentState("thermostatMode")?.value
	def lastTriedMode = state.lastTriedMode ?: currentMode ?: "heat"
	def modeNumber;
	Integer setpointModeNumber;
	def modeToSendInString;
    switch (value) {
        case "heat":
        case "emergency heat":
        case "auto":
            return heat()
        
        case "eco":
        case "cool":
            return eco()
        
        default:
            return off()
    }
}

def updated() {
    parameterSetting()
}

def parameterSetting() {
    def lockmode = null
    def valid_lock = 0

    log.info "lock : $settings.lock"
    if (settings.lock == "Yes") {
        lockmode = 0x01
        valid_lock = 1
    }
    else if (settings.lock == "No") {
        lockmode = 0x00
        valid_lock = 1
    }
    
    if (valid_lock == 1)
    {
    	log.info "lock valid"
        def cmds = []
        
        cmds+= zigbee.writeAttribute(0x204, 0x01, 0x30, lockmode)	//Write Lock Mode
        cmds+= refresh()
        return cmds
    }
    else {
    	log.info "nothing valid"
    }
}
