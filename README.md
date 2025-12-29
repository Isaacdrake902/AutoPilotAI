# AutoPilotAI

**AutoPilotAI** is an advanced Android automation agent powered by Vision Language Models. It allows users to control their device using natural language commands, bridging the gap between human intent and mobile interface execution.

By leveraging privileged system access and **Jetpack Compose** for a modern UI, AutoPilotAI can perceive screen content, plan actions, and execute complex workflows without requiring root access.

##  Features

-   **Natural Language Control**: Describe tasks in plain English (e.g., "Order a burger", "Send a message to Mom").
-   **Visual Screen Understanding**: Uses VLM (Vision Language Model) to analyze UI elements, text, and layout on the screen.
-   **Intelligent Planning**: Decomposes complex goals into executables steps (Click, Type, Scroll, Home, Back).
-   **Non-Root Automation**: Utilizes **Shizuku** to execute ADB commands directly on the device, ensuring high compatibility and security.
-   **App Management**: Smart scanning and categorization of installed applications.
-   **Privacy-Focused**: Sensitive screen detection (e.g., password fields) automatically pauses execution.

##  Technical Stack

### Core Technologies
-   **Language**: Kotlin
-   **UI Framework**: Jetpack Compose (Material3)
-   **Networking**: OkHttp (VLM API communication)
-   **Concurrency**: Kotlin Coroutines & Flow

### Key Components
-   **MobileAgent**: The central brain that manages state, memory, and VLM interactions. It maintains the `AgentState` and coordinates the execution loop.
-   **DeviceController**: A simplified abstraction layer for device interactions. It handles:
    -   Screen attributes retrieval (`wm size`)
    -   Input simulation (`input tap`, `input text`)
    -   Screenshot capture (via `screencap`)
    -   App launching protocols
-   **OverlayService**: A foreground service that draws a floating status window over other apps, providing visual feedback and an immediate "Stop" mechanism.
-   **AppScanner**: Scans installed packages, categorizes them (Social, Shopping, etc.), and builds a semantic index for smart searching.

##  Architecture

The application follows a reactive **MVI (Model-View-Intent)** architecture pattern:

1.  **UI Layer (Compose)**: Observes `AgentState` and renders the current status (Idle, Planning, Executing). User inputs are dispatched as events.
2.  **Logic Layer (Agent)**:
    -   **Planner**: detailed step-by-step breakdown of user instructions.
    -   **Executor**: interfacing with `ToolManager` to perform specific actions.
    -   **Memory**: Maintains conversation context and history.
3.  **Data Layer**:
    -   `SettingsManager` (DataStore) for configuration.
    -   `SkillRegistry` for predefined capabilities.
    -   `VLMClient` for AI model communication.

##  Setup & Prerequisites

### Requirements
-   **Android Device**: Android 8.0 (Oreo) or higher (API Level 26+).
-   **Shizuku**: Must be installed and running.

### Installation
1.  **Clone the repository**:
    ```bash
    git clone https://github.com/soumithganji/AutoPilotAI.git
    ```
2.  **Open in Android Studio**: Sync Gradle project.
3.  **Build & Run**:
    ```bash
    ./gradlew installDebug
    ```

### Configuration
1.  **Install Shizuku**: Download from Google Play or GitHub.
2.  **Start Shizuku**:
    -   **Rooted Devices**: Direct start.
    -   **Non-Rooted**: Use Wireless Debugging key pairing (Android 11+) or connect to PC via USB and run the start script.
3.  **Authorize AutoPilotAI**: Open Shizuku -> Manage Apps -> Grant access to AutoPilotAI.
4.  **Grant Permissions**: Accept Overlay and Storage permissions when prompted.