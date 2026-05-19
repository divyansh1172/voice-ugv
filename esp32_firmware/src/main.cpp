#include <Arduino.h>
#include "ble_receive.h"
#include "motor.h"

// MFCC, DTW, and templates.h are no longer needed on the ESP32.
// All recognition happens on the Android phone.
// The phone sends a plain command string: "forward" / "back" / "left" / "right" / "stop"
// The Ugv will keep on going forward or back till it gets stop and will perform approx 90 degree turns for left and right.
void setup() {
    Serial.begin(115200);
    motor_init();
    ble_init();
    Serial.println("ESP32 UGV ready — waiting for command codes (f/b/l/r/s) via BLE");
}

void loop() {
    char cmd[16] = {0};
    if (!ble_receive_command(cmd, sizeof(cmd))) {
        delay(5);
        return;
    }

    Serial.printf("Command received: %s\n", cmd);

    // Optimized single-character dispatch
    char code = cmd[0];
    if      (code == 'f' || strcmp(cmd, "forward") == 0) { motor_forward(); }
    else if (code == 'b' || strcmp(cmd, "back")    == 0) { motor_back();    }
    else if (code == 'l' || strcmp(cmd, "left")    == 0) { motor_left();    delay(600); motor_stop(); }
    else if (code == 'r' || strcmp(cmd, "right")   == 0) { motor_right();   delay(600); motor_stop(); }
    else if (code == 's' || strcmp(cmd, "stop")    == 0) { motor_stop();    }
    else { Serial.printf("Unknown command code: %s\n", cmd); }

    Serial.println("Ready for next command");
}
