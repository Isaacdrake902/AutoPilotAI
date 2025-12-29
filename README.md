# AutoPilotAI

**AutoPilotAI** is an advanced Android automation agent powered by Vision Language Models (VLMs). It bridges the gap between human intent and mobile interface execution, allowing users to control their devices using natural language commands.

Unlike traditional accessibility tools that rely on hardcoded selectors, AutoPilotAI uses AI to "see" and "understand" the screen, making it resilient to UI changes and capable of handling complex, multi-step workflows.

##  Key Features

-   **Natural Language Control**: Simply say "Order me a coffee" or "Send a message to Mom," and the agent handles the rest.
-   **Visual Intelligence**: Powered by state-of-the-art VLMs (GPT-4V, Qwen-VL, Claude 3), the agent analyzes screenshots to identify UI elements, verify states, and validate actions.
-   **Intelligent Planning**:
    -   **Manager**: Decomposes high-level goals into actionable sub-tasks.
    -   **Executor**: Performs precise interactions (Click, Scroll, Type, Home, Back).
    -   **Reflector**: "Reflects" on actions by comparing pre- and post-action states to ensure success.
-   **Non-Root Automation**: leverages **Shizuku** to execute ADB commands directly on-device, offering powerful automation without rooting the phone.
-   **Privacy-First Design**:
    -   **Sensitive Content Detection**: Automatically pauses execution and blanks screens when password fields or payment information are detected.
    -   **Local Processing**: App searching and basic logic run locally where possible.

##  Architecture

AutoPilotAI follows a modern, reactive architecture comprising three main layers:

### 1. The Agent Core (`MobileAgent`)
The "brain" of the application, responsible for the perception-action loop:
-   **Observation**: Captures current screen state via ADB or Accessibility services.
-   **Reasoning**: Sends visual context to the VLM to decide the next best action.
-   **Execution**: Translates VLM decisions into low-level input events.
-   **Memory**: Maintains a `ConversationMemory` of the current task to handle context and multi-turn instructions.

### 2. Skill System
A hybrid approach combining general intelligence with specialized capabilities:
-   **General Skills**: Use VLM reasoning for unseen apps and dynamic UIs.
-   **Specialized Skills**: High-confidence, optimized paths for frequent tasks (via `SkillRegistry`).
-   **App Scanner**: Semantically indexes installed apps to quickly find the right tool for the job.

### 3. UI & Services
-   **OverlayService**: A floating system overlay that provides real-time feedback (Thought/Action logs) and an immediate "Stop" control.
-   **Jetpack Compose**: Modern, reactive UI monitoring the `AgentState` (Idle, Planning, Executing).

##  Technical Stack

-   **Language**: Kotlin
-   **UI**: Jetpack Compose (Material3)
-   **Concurrency**: Kotlin Coroutines & Flows
-   **AI/VLM**: OpenAI API-compatible client (supports GPT-4o, Qwen-VL, etc.)
-   **Networking**: OkHttp

##  Getting Started


### Installation

1.  **Clone the Repository**:
    ```bash
    git clone https://github.com/soumithganji/AutoPilotAI.git
    ```
2.  **Open in Android Studio**: Open the project folder.
3.  **Sync & Build**: specificy the `app` configuration.
    ```bash
    ./gradlew installDebug
    ```

### Configuration
1.  Launch **AutoPilotAI**.
2.  Grant the **Shizuku** permission when prompted.
3.  Grant **Overlay** and **Accessibility** permissions to allow the agent to see and interact with the screen.
4.  Configure your **VLM API Key** (OpenAI/DashScope/Anthropic) in the settings menu.

##  Privacy & Safety
AutoPilotAI is designed with safety guardrails:
-   **Human-in-the-Loop**: Critical actions (like payments) trigger a confirmation dialog.
-   **Emergency Stop**: The floating overlay always remains visible, allowing you to terminate execution instantly.