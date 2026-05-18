# 🛰️ Voice-Controlled UGV (Voice-UGV)

A high-performance, **100% offline** voice-controlled robotics platform. This project offloads computationally intensive speech recognition (MFCC + DTW) from a microcontroller to an Android smartphone, achieving low-latency, language-agnostic control of a differential-drive vehicle.

## 🏗️ Project Architecture

The system follows a "Brain-and-Driver" split architecture:

### 1. Android "Brain" (`android_app`)
*   **Role**: Handles audio capture (8kHz), Digital Signal Processing (DSP), and decision making.
*   **Algorithms**: 
    *   **MFCC**: Mel-frequency cepstral coefficients for feature extraction.
    *   **DTW**: Dynamic Time Warping (Sakoe-Chiba band) for pattern matching against templates.
*   **Features**: Best-of-N template matching (up to 10 samples per command), real-time BLE controller, and in-app voice training.
*   **Tech Stack**: Java, Android SDK (Target 34), Gradle.

### 2. ESP32 "Driver" (`esp32_firmware`)
*   **Role**: Manages low-level hardware execution.
*   **Hardware**: ESP32 DevKit V1 + L293D Motor Driver + DC Gear Motors.
*   **Communication**: Bluetooth Low Energy (BLE).
*   **Tech Stack**: C++/Arduino, PlatformIO.

### 3. Research & Documentation
*   **Paper**: `docs/paper/main.tex` (IEEE Conference format).
*   **Results**: 90-95% accuracy, ~1.6s latency, support for English, Hindi, Gujarati, and Tamil.

---

## 🛠️ Getting Started

### Hardware Wiring
| ESP32 Pin | Component | L293D Pin |
| :--- | :--- | :--- |
| GPIO 32 | Motor Left (IN1) | IN1 |
| GPIO 33 | Motor Left (IN2) | IN2 |
| GPIO 25 | Motor Right (IN3) | IN3 |
| GPIO 26 | Motor Right (IN4) | IN4 |

### Building ESP32 Firmware
1. Open `esp32_firmware` in VS Code with **PlatformIO**.
2. Flash using the `esp32dev` environment.
3. Monitor at **115200 baud**.

### Building Android App
1. Open `android_app` in **Android Studio**.
2. Use **Java 17** toolchain.
3. Build and install the APK.
4. **Important**: The app expects the ESP32 to have a specific MAC address (`EC:94:CB:4A:6E:9E`), which the firmware spoofs automatically.

### Template Conversion
If you modify the default templates in `esp32_firmware/include/templates.h`, run the conversion tool:
```bash
python tools/convert_templates.py --templates esp32_firmware/include/templates.h --out android_app/app/src/main/assets/templates
```

---

## 🚦 Development Conventions

*   **BLE Device Name**: `ESP32_UGV`
*   **BLE MAC Spoofing**: The ESP32 spoofs its base MAC to `EC:94:CB:4A:6E:9C` so that the BLE public address becomes `EC:94:CB:4A:6E:9E`.
*   **Supported Commands**:
    *   `forward`: Continuous movement.
    *   `back`: Continuous movement.
    *   `left`: ~90-degree turn (timed).
    *   `right`: ~90-degree turn (timed).
    *   `stop`: Immediate halt.
*   **Asset Storage**: Voice templates are stored as little-endian binary files (`.bin`) in the Android `assets/templates/` folder.

## 📂 Directory Structure
*   `android_app/`: Android source code.
*   `esp32_firmware/`: ESP32 firmware source code.
*   `tools/`: Utility scripts (e.g., template conversion).
*   `docs/paper/`: LaTeX assets and compiled research paper.
*   `releases/`: Pre-built APK files.

---

## 🗺️ Roadmap & Pending Tasks (from STATUS.md)

### 1. Accuracy & Feature Tuning
*   **Threshold Adjustment**: Relax the `THRESHOLD` in `DTWMatcher.java` (currently 12.0) to better support the high-resolution 39-coefficient features.
*   **Template Migration**: Re-record voice templates in-app to fully utilize the new 39-coefficient format.

### 2. ESP32 Firmware Upgrades
*   **Smooth Ramping**: Implement PWM for motor control to allow for smooth acceleration/deceleration.
*   **Precision Turning**: Replace the hardcoded `delay(600)` logic with a more reliable method (e.g., using an IMU or encoders).

### 3. Repository Maintenance
*   **[DONE] Structural Refactor**: Flattened the directory tree and moved source code to root.
*   **[DONE] Cleanup**: Removed LaTeX build artifacts.
