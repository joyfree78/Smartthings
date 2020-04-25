/**
 *  Tuya Window Shade (v.0.2.3.1)
 *	Copyright 2020 iquix
 *
 *	Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *	in compliance with the License. You may obtain a copy of the License at:
 *
 *		http://www.apache.org/licenses/LICENSE-2.0
 *
 *	Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *	on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *	for the specific language governing permissions and limitations under the License.
 */

import groovy.json.JsonOutput

metadata {
	definition(name: "Tuya Window Shade", namespace: "iquix", author: "iquix", ocfDeviceType: "oic.d.blind", vid: "generic-shade") {
		capability "Actuator"
		capability "Configuration"
		capability "Window Shade"
		capability "Window Shade Preset"
		capability "Switch Level"
		
		command "pause"

		fingerprint endpointId: "0x01", profileId: "0104", deviceId: "0100", inClusters: "0000, 0003, 0004, 0005, 0006", outClusters: "0019", deviceJoinName: "Tuya Curtain"
	}

	preferences {
		input "preset", "number", title: "Preset position", description: "Set the window shade preset position", defaultValue: 50, range: "0..100", required: false, displayDuringSetup: false
		input "reverse", "enum", title: "Direction", description: "Set direction of curtain motor. [WARNING: Please set curtain position to 50% before changing this preference option.]", options: ["Forward", "Reverse"], defaultValue: "Forward", required: false, displayDuringSetup: false
		input "fixpercent", "enum", title: "Fix percent", description: "You need to fix percent unless open is 100% and close is 0%. [WARNING: Please set curtain position to 50% before changing this preference option.]", options: ["Fix percent", "Leave it"], defaultValue: "Leave it", required: false, displayDuringSetup: false
	}

	tiles(scale: 2) {
		multiAttributeTile(name:"windowShade", type: "generic", width: 6, height: 4) {
			tileAttribute("device.windowShade", key: "PRIMARY_CONTROL") {
				attributeState "open", label: 'Open', action: "close", icon: "http://www.ezex.co.kr/img/st/window_open.png", backgroundColor: "#00A0DC", nextState: "closing"
				attributeState "closed", label: 'Closed', action: "open", icon: "http://www.ezex.co.kr/img/st/window_close.png", backgroundColor: "#ffffff", nextState: "opening"
				attributeState "partially open", label: 'Partially open', action: "close", icon: "http://www.ezex.co.kr/img/st/window_open.png", backgroundColor: "#d45614", nextState: "closing"
				attributeState "opening", label: 'Opening', action: "pause", icon: "http://www.ezex.co.kr/img/st/window_open.png", backgroundColor: "#00A0DC", nextState: "partially open"
				attributeState "closing", label: 'Closing', action: "pause", icon: "http://www.ezex.co.kr/img/st/window_close.png", backgroundColor: "#ffffff", nextState: "partially open"
			}
		}
		standardTile("contPause", "device.switch", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
			state "pause", label:"", icon:'st.sonos.pause-btn', action:'pause', backgroundColor:"#cccccc"
		}
		standardTile("presetPosition", "device.presetPosition", width: 2, height: 2, decoration: "flat") {
			state "default", label: "Preset", action:"presetPosition", icon:"st.Home.home2"
		}
		standardTile("refresh", "device.refresh", inactiveLabel: false, decoration: "flat", width: 2, height: 1) {
			state "default", label:"", action:"refresh.refresh", icon:"st.secondary.refresh"
		}
		valueTile("shadeLevel", "device.level", width: 4, height: 1) {
			state "level", label: 'Shade is ${currentValue}% up', defaultState: true
		}
		controlTile("levelSliderControl", "device.level", "slider", width:2, height: 1, inactiveLabel: false) {
			state "level", action:"switch level.setLevel"
		}

		main "windowShade"
		details(["windowShade", "contPause", "presetPosition", "shadeLevel", "levelSliderControl", "refresh"])
	}
}

private getCLUSTER_TUYA() { 0xEF00 }
private getSETDATA() { 0x00 }

// tuya DP type
private getDP_TYPE_BOOL() { "01" }
private getDP_TYPE_VALUE() { "02" }
private getDP_TYPE_ENUM() { "04" }


// Parse incoming device messages to generate events
def parse(String description) {
	if (description?.startsWith('catchall:') || description?.startsWith('read attr -')) {
		Map descMap = zigbee.parseDescriptionAsMap(description)		
		log.debug descMap
		if (descMap?.clusterInt==CLUSTER_TUYA) {
			if ( descMap?.command == "01" || descMap?.command == "02" ) {
				def dp = zigbee.convertHexToInt(descMap?.data[2])
				def data = descMap?.data[6..-1]
				log.debug "dp=" + dp + "  data=" + data
				switch (dp) {
					case 0x07: // 0x07: Work state -- Started moving (triggered by transmitter or pulling on curtain)
						if (device.currentValue("level")==0) {
							log.debug "moving from position 0 : must be opening"
							levelEventMoving(100)
						} else if (device.currentValue("level")==100) {
							log.debug "moving from position 100 : must be closing"
							levelEventMoving(0)
						}
						break
					case 0x01: // 0x01: Control -- Opening/closing/stopping (triggered from Zigbee)
						if (data[0] == ((REVERSE_MODE) ? "00" : "02")) {
							log.debug "opening"
							levelEventMoving(100)
						} else if (data[0] == ((REVERSE_MODE) ? "02" : "00")) {
							log.debug "closing"
							levelEventMoving(0)
						}
						break
					case 0x02: // 0x02: Percent control -- Started moving to position (triggered from Zigbee)
						def pos = levelVal(zigbee.convertHexToInt(data[3]))
						log.debug "moving to position: "+pos
						levelEventMoving(pos)
						break
					case 0x03: // 0x03: Percent state -- Arrived at position
						def pos = levelVal(zigbee.convertHexToInt(data[3]))
						log.debug "arrived at position: "+pos
						levelEventArrived(pos)
						break
				}
			}
		}
	}
}

private levelEventMoving(currentLevel) {
	def lastLevel = device.currentValue("level")
	log.debug "levelEventMoving - currentLevel: ${currentLevel} lastLevel: ${lastLevel}"
	if (lastLevel == "undefined" || currentLevel == lastLevel) { //Ignore invalid reports
		log.debug "Ignore invalid reports"
	} else {
		if (lastLevel < currentLevel) {
			sendEvent([name:"windowShade", value: "opening"])
		} else if (lastLevel > currentLevel) {
			sendEvent([name:"windowShade", value: "closing"])
		}
	}
}

private levelEventArrived(level) {
	if (level == 0) {
		sendEvent(name: "windowShade", value: "closed")
	} else if (level == 100) {
		sendEvent(name: "windowShade", value: "open")
	} else if (level > 0 && level < 100) {
		sendEvent(name: "windowShade", value: "partially open")
	} else {
		sendEvent(name: "windowShade", value: "unknown")
		return
	}
	sendEvent(name: "level", value: (level))
}

def close() {
	log.info "close()"
	def currentLevel = device.currentValue("level")
	if (currentLevel == 0) {
		sendEvent(name: "windowShade", value: "closed")
		return
	}
	sendTuyaCommand("01", DP_TYPE_ENUM, (REVERSE_MODE) ? "02" : "00")
}

def open() {
	log.info "open()"
	def currentLevel = device.currentValue("level")
	if (currentLevel == 100) {
		sendEvent(name: "windowShade", value: "open")
		return
	}
	sendTuyaCommand("01", DP_TYPE_ENUM, (REVERSE_MODE) ? "00" : "02")
}

def pause() {
	log.info "pause()"
	sendTuyaCommand("01", DP_TYPE_ENUM, "01")
}

def setLevel(data, rate = null) {
	log.info "setLevel("+data+")"
	def currentLevel = device.currentValue("level")
	if (currentLevel == data) {
		sendEvent(name: "level", value: currentLevel)
		return
	}
	sendTuyaCommand("02", DP_TYPE_VALUE, zigbee.convertToHexString(levelVal(data), 8))
}


def presetPosition() {
	setLevel(preset ?: 50)
}

def installed() {
	sendEvent(name: "supportedWindowShadeCommands", value: JsonOutput.toJson(["open", "close", "pause"]), displayed: false)
}

def configure() {
	log.info "configure()"
	setLevel(50)
}

private sendTuyaCommand(dp, dp_type, data) {
	zigbee.command(CLUSTER_TUYA, SETDATA, "00" + PACKET_ID + dp + dp_type + zigbee.convertToHexString(data.length()/2, 4) + data )
}

private getPACKET_ID() {
	state.packetID = ((state.packetID ?: 0) + 1 ) % 256
	return zigbee.convertToHexString(state.packetID)
}

private getREVERSE_MODE() { 
	return (reverse == "Reverse")
}

private levelVal(n) {
	return (int)((REVERSE_MODE ^ (fixpercent != "Fix percent")) ? n : 100-n)
}