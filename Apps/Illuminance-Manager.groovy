/**
 *  MIT License
 *  Copyright 2020 Jonathan Bradshaw (jb@nrgup.net)
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
*/
import com.hubitat.app.DeviceWrapper
import com.hubitat.hub.domain.Event

definition (
    name: 'Illuminance Manager',
    namespace: 'nrgup',
    author: 'Jonathan Bradshaw',
    category: 'Lighting',
    description: 'Manage your light illuminance',
    iconUrl: '',
    iconX2Url: '',
    installOnOpen: true,
    iconX3Url: ''
)

preferences {
    page(name: 'configuration', install: true, uninstall: true) {
        section {
            label title: 'Application Label',
                  required: false
        }

        section {
            input name: 'masterEnable',
                  type: 'bool',
                  title: 'Application enabled',
                  required: false,
                  defaultValue: true
        }

        section('Managed Lights', hideable: true, hidden: true) {
            input name: 'dimmableDevices',
                  type: 'capability.switchLevel',
                  title: 'Select lights to adjust brightness level',
                  multiple: true,
                  required: false

            input name: 'dimmableOnDevices',
                  type: 'capability.switchLevel',
                  title: 'Select lights to adjust level only when on',
                  multiple: true,
                  required: false
        }

        section('Illuminance Calculation', hideable: true, hidden: true) {
            input name: 'luxDevices',
                  type: 'capability.illuminanceMeasurement',
                  title: 'Lux Sensor Devices',
                  multiple: true,
                  required: true

            input name: 'minimumLevel',
                  type: 'number',
                  title: 'Minimum brightness level for lamps',
                  required: true,
                  defaultValue: 10

            input name: 'offset',
                  type: 'number',
                  title: '<a href="https://www.desmos.com/calculator/vi0qou21ol" target=”_blank”>Logarithm offset</a> - (a) Moves the lighting curve up and down, without changing the curve shape',
                  required: true,
                  range: '3..10000'
                  defaultValue: 4500

            input name: 'base',
                  type: 'decimal',
                  title: '<a href="https://www.desmos.com/calculator/vi0qou21ol" target=”_blank”>Logarithm base</a> - (b) Changes the shape of the curve by making it slightly stipper or flatter',
                  required: true,
                  defaultValue: 1.5

            input name: 'multiplier',
                  type: 'number',
                  title: '<a href="https://www.desmos.com/calculator/vi0qou21ol" target=”_blank”>Logarithm multiplier</a> - (c) Changes the curve shape more drastically',
                  required: true,
                  range: '3..3000'
                  defaultValue: 200
        }

        section('General Settings', hideable: true, hidden: true) {
            input name: 'transitionSeconds',
                  type: 'number',
                  title: 'Transition seconds for level changes',
                  range: '0..60',
                  required: true,
                  defaultValue: 20

            input name: 'reenableDelay',
                  type: 'number',
                  title: 'Automatically re-enable control after specified minutes (0 for never)',
                  range: '0..600',
                  required: false,
                  defaultValue: 60

            input name: 'sunriseOffset',
                  type: 'number',
                  title: 'Sunrise Offset (+/-)',
                  range: '-600..600',
                  required: false,
                  defaultValue: 0

            input name: 'sunsetOffset',
                  type: 'number',
                  title: 'Sunset Offset (+/-)',
                  range: '-600..600',
                  required: false,
                  defaultValue: 0
        }

        section('Override Settings', hideable: true, hidden: true) {
            input name: 'disabledSwitch',
                  type: 'capability.switch',
                  title: 'Select switch to enable/disable manager'

            input name: 'disabledSwitchValue',
                    title: 'Disable lighting manager when switch is',
                    type: 'enum',
                    required: true,
                    defaultValue: 'off',
                    options: [
                        on: 'on',
                        off: 'off'
                    ]

            input name: 'disabledModes',
                  type: 'mode',
                  title: 'Select mode(s) where lighting manager should be disabled',
                  multiple: true
        }

        section {
            input name: 'updateInterval',
                    title: 'Update interval',
                    type: 'enum',
                    defaultValue: 5,
                    options: [
                        5: 'Every 5 minutes',
                        10: 'Every 10 minutes',
                        15: 'Every 15 minutes',
                        30: 'Every 30 minutes',
                        45: 'Every 30 minutes'
                    ]

            input name: 'logEnable',
                  type: 'bool',
                  title: 'Enable Debug logging',
                  required: false,
                  defaultValue: true
        }
    }
}

// Called when the app is first created.
void installed() {
    log.info "${app.name} installed"
}

// Called when the app is removed.
void uninstalled() {
    unsubscribe()
    log.info "${app.name} uninstalled"
}

// Called when the settings are updated.
void updated() {
    log.info "${app.name} configuration updated"
    log.debug settings
    initialize()
}

// Called when the driver is initialized.
void initialize() {
    log.info "${app.name} initializing"
    unschedule()
    unsubscribe()
    state.brightness = 0
    state.disabledDevices = [:]
    state.lastUpdate = 0

    if (masterEnable) {
        subscribe(dimmableOnDevices, 'switch', 'updateLamp')
        subscribe(dimmableDevices, 'switch', 'updateLamp')
        subscribe(dimmableOnDevices, 'level', 'levelCheck')
        subscribe(dimmableDevices, 'level', 'levelCheck')

        // Update lamps on defined schedule
        int interval = settings.updateInterval as int
        log.info "Scheduling periodic updates every ${interval} minute(s)"
        schedule("0 */${interval} * * * ?", 'levelUpdate')
        levelUpdate()
    }
}

// https://www.desmos.com/calculator/vi0qou21ol
private int calculateLevel(Integer illum) {
    // assuming max illum of 10,000 - 13,000
    BigDecimal x = Math.max(illum, 1)
    BigDecimal a = settings.offset
    BigDecimal b = settings.base
    BigDecimal c = settings.multiplier
    BigDecimal y = ( Math.log10(1 / x) / Math.log10(b) ) * c + a
    if (logEnable) { log.debug "calculateLevel: x=${x} a=${a} b=${b} c=${c} y=${y}" }
    if (y < 1) { y = 1 }
    if (y > 100) { y = 100 }
    return y as int
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void levelUpdate() {
    long currentTime = now()
    int level = state.brightness
    Map after = getSunriseAndSunset(
        sunriseOffset: settings.sunriseOffset ?: 0,
        sunsetOffset: settings.sunsetOffset ?: 0
    )

    if (currentTime >= after.sunrise.time && currentTime <= after.sunset.time) {
        int lux = currentLuxValue()
        level = calculateLevel(lux)
        if (level < settings.minimumLevel) { level = settings.minimumLevel }
        log.info "${app.name} Brightness level calculated at ${level}% based on ${lux} lux reading"
    } else {
        long midNight = after.sunset.time + ((after.sunset.time - after.sunrise.time) / 2)
        int min = settings.minimumLevel ?: 1
        int max = calculateLevel(1)
        int range = max - min
        if (currentTime > after.sunset.time && currentTime < midNight) {
            if (logEnable) { log.debug 'Current time is after sunset and before midnight' }
            level = max - ((currentTime - after.sunset.time) / (midNight - after.sunset.time) * range)
        } else {
            if (logEnable) { log.debug 'Current time is after midnight and before sunrise' }
            level = min
        }
        log.info "${app.name} Brightness level calculated at ${level}% based on current night time"
    }

    state.brightness = level
    state.lastUpdate = now()
    updateLamps()
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void levelCheck(Event evt) {
    DeviceWrapper device = evt.device
    int value = evt.value as int
    int brightness = state.brightness
    int transition = settings.transitionSeconds

    // ignore any changes shortly after making an update
    if (value != brightness && now() - (state.lastUpdate as long) <= transition * 1100) {
        if (logEnable) {
            log.debug "Ignoring ${device} level change to ${value}% (within ${transition}s change window)"
        }
        return
    }

    if ((value > brightness + 5 || value < brightness - 5) && !state.disabledDevices.containsKey(device.id)) {
        log.info "${app.name} disabling ${device} for illuminance management (light now at ${value}%)"
        state.disabledDevices.put(device.id, now())
    } else if (value < brightness + 5 && value > brightness - 5 && device.id in state.disabledDevices) {
        log.info "${app.name} re-enabling ${device} for illuminance management (light now at ${value}%)"
        state.disabledDevices.remove(device.id)
    }
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void updateLamp(Event evt) {
    int brightness = state.brightness
    DeviceWrapper device = evt.device

    if (evt.value == 'off' && device.id in state.disabledDevices) {
        log.info "${app.name} Re-enabling ${device} for illuminance management (light turned off)"
        state.disabledDevices.remove(device.id)
        return
    }

    if (location.mode in disabledModes) {
        log.info "${app.name} Manager is disabled due to mode ${location.mode}"
        return
    }

    if (disabledSwitch && disabledSwitch.currentValue('switch') == disabledSwitchValue) {
        log.info "${app.name} Manager is disabled due to switch ${disabledSwitch} set to ${disabledSwitchValue}"
        return
    }

    if (device.currentValue('level') != brightness) {
        log.info "${app.name} Setting ${device} level to ${brightness}%"
        device.setLevel(brightness, settings.transitionSeconds as int)
    }
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void updateLamps() {
    int brightness = state.brightness
    Map disabled = state.disabledDevices

    if (location.mode in disabledModes) {
        log.info "${app.name} Manager is disabled due to mode ${location.mode}"
        return
    }

    // Remove disabled devices that have timed out
    if (settings.reenableDelay) {
        long expire = now() - (settings.reenableDelay * 60000)
        disabled.values().removeIf { v -> v <= expire }
    }

    log.info "${app.name} Starting illuminance level updates to lights"

    settings.dimmableOnDevices?.each { device ->
        if (!disabled.containsKey(device.id) &&
            device.currentValue('level') != brightness &&
            device.currentValue('switch') == 'on') {
            if (logEnable) { log.debug "Setting ${device} level to ${brightness}%" }
            device.setLevel(brightness, settings.transitionSeconds as int)
        }
    }

    settings.dimmableDevices?.each { device ->
        if (!disabled.containsKey(device.id) && device.currentValue('level') != brightness) {
            if (logEnable) { log.debug "Setting ${device} level to ${brightness}%" }
            device.setLevel(brightness, settings.transitionSeconds as int)
        }
    }
}

/*
 * Average of lux sensors
 */
private int currentLuxValue() {
    int total = 0
    int count = 0
    luxDevices.each { d ->
        int value = d.currentValue('illuminance')
        if (value) { count++ }
        total += value
    }
    return Math.round(total / count)
}

