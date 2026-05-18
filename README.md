# 🛰️ Voice-Controlled UGV (Unmanned Ground Vehicle)

A sophisticated, **100% offline**, voice-controlled robotics platform that combines embedded systems, mobile application development, and advanced digital signal processing. This project allows users to control a differential-drive vehicle using natural voice commands processed locally on an Android device and transmitted via Bluetooth Low Energy (BLE).

---

## 🚀 Key Features

*   **Offline Voice Recognition**: No cloud dependency or internet connection required. All processing happens locally for zero latency and total privacy.
*   **High-Accuracy DSP Pipeline**:
    *   **MFCC Extraction**: Extracts Mel-frequency cepstral coefficients for robust vocal feature representation.
    *   **Delta-Delta Coefficients**: Captures vocal acceleration features to distinguish between similar-sounding commands (e.g., "Left" vs "Right").
    *   **Energy-based VAD**: Voice Activity Detection automatically trims silence and background noise from recordings.
*   **Pattern Matching with DTW**: Uses Dynamic Time Warping with a **Sakoe-Chiba band** and **Early-Exit optimization** for lightning-fast matching.
*   **Best-of-N Template Strategy**: Supports up to 10 personalized voice templates per command for user adaptation and multi-speaker support.
*   **Professional Controller UI**: 
    *   Joystick-style manual overrides.
    *   Emergency STOP safety button.
    *   Real-time BLE auto-reconnect logic.
    *   In-app training interface for recording custom voice profiles.

---

## 🛠️ System Architecture

The project is split into two specialized nodes:

### 1. Android "Brain" App (`android_app`)
*   **Role**: Handles audio capture (8kHz mono), signal processing, and decision-making.
*   **Tech Stack**: Java, Android SDK, FFT/DSP algorithms.
*   **Parallel Processing**: Matching logic is parallelized across CPU cores to minimize latency.

### 2. ESP32 "Driver" Firmware (`esp32_firmware`)
*   **Role**: Manages low-level motor control and BLE communication.
*   **Hardware**: ESP32 Microcontroller, L293D Motor Driver, DC Gear Motors.
*   **Protocol**: Bluetooth Low Energy (BLE) with custom GATT services.

---

## 🔌 Hardware Setup

### Components List
| Component | Purpose |
| :--- | :--- |
| **ESP32 DevKit V1** | Main Microcontroller & BLE Hub |
| **L293D IC** | Dual H-Bridge Motor Driver |
| **UGV Chassis** | 2WD or 4WD Differential Drive |
| **Li-ion Battery** | 7.4V - 12V Power Supply |
| **Android Smartphone** | Primary Controller & DSP Node |

### Wiring Schematic
The ESP32 communicates with the L293D to control direction and speed:
*   **Motor A (Left)**: GPIO 32 (IN1), GPIO 33 (IN2)
*   **Motor B (Right)**: GPIO 25 (IN3), GPIO 26 (IN4)
*   **Power**: VCC2 (L293D) to External Battery; VCC1 to ESP32 3.3V.

---

## ⚙️ Installation & Setup

### Android App
1.  Open the `android_app` folder in **Android Studio** (Hedgehog or later).
2.  Ensure you have **Java 17** selected as your Gradle JDK.
3.  Build and install the APK onto your device.
4.  Grant **Microphone** and **Bluetooth** permissions upon launch.

### ESP32 Firmware
1.  Open the `esp32_firmware` folder in **VS Code** with the **PlatformIO** extension.
2.  Connect your ESP32 via USB.
3.  Click **Upload** to flash the firmware.
4.  Open the Serial Monitor (115200 baud) to confirm the device is advertising.

---

## 🚦 Usage
1.  Power on the UGV and open the app.
2.  The app will automatically connect to the `ESP32_UGV`. The status bar will turn **Green**.
3.  **Training**: Go to "Settings/Training" to record your voice. Record 5-10 samples per command ("Forward", "Back", "Left", "Right", "Stop").
4.  **Control**: Return to the main screen. Hold the **Blue Speak Button** and say a command. The UGV responds within ~200ms of you releasing the button.
5.  **Manual Override**: Use the arrow buttons on the screen for fine-tuned positioning.
6.  **Emergency**: Hit the **Large Red STOP Button** to kill all motor activity instantly.

---

## 📜 Development Notes
This project was developed as a high-performance demonstration of edge computing and hardware integration. The DTW algorithm was manually ported and optimized for mobile processors, achieving recognition speeds comparable to cloud-based alternatives while remaining entirely private and local.

---

*Developed by Divyansh Maheshwari, Shriyansh Chawda and GVV Sharma*
