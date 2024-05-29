# MuPDF-Viewer

MuPDF-Viewer is an Android application written in Kotlin that allows users to view PDF files using the MuPDF library.

## Table of Contents

- [Overview](#overview)
- [Installation](#installation)
- [Build Instructions](#build-instructions)
- [Running the Application](#running-the-application)
- [Contributing](#contributing)
- [License](#license)

## Overview

MuPDF-Viewer is an open-source Android application developed in Kotlin. It leverages the MuPDF library to provide a lightweight and efficient PDF viewing experience on Android devices.

## Installation

To set up the development environment for MuPDF-Viewer, follow these steps:

1. **Install Android Studio**: Download and install the latest version of [Android Studio](https://developer.android.com/studio).

2. **Clone the Repository**: Clone this repository to your local machine using Git.
    ```sh
    git clone https://github.com/tuxxon/mupdf-viewer.kotlin.git
    ```

3. **Open the Project**: Launch Android Studio and open the cloned project directory.

4. **Install SDK and Dependencies**: Ensure you have the required Android SDKs and dependencies installed. Android Studio will prompt you to install any missing components.

## Build Instructions

To build the MuPDF-Viewer application, follow these steps:

1. **Sync Project**: Open the project in Android Studio and let it sync to download all necessary Gradle dependencies.

2. **Build the Project**: Use the "Build" menu in Android Studio to build the project or use the following Gradle command in the terminal.
    ```sh
    ./gradlew build
    ```

3. **Resolve Issues**: If there are any build issues, resolve them as indicated by Android Studio or the terminal output.

## Running the Application

To run the MuPDF-Viewer application on an Android device or emulator, follow these steps:

1. **Connect Device**: Connect your Android device via USB or start an Android emulator.

2. **Run the Project**: Click the "Run" button in Android Studio or use the following Gradle command in the terminal.
    ```sh
    ./gradlew installDebug
    ```

3. **Launch the App**: The application should be installed and launched on the connected device or emulator.

## Contributing

We welcome contributions to the MuPDF-Viewer project. To contribute, please follow these steps:

1. **Fork the Repository**: Fork this repository to your GitHub account.

2. **Create a Branch**: Create a new branch for your feature or bug fix.
    ```sh
    git checkout -b feature-name
    ```

3. **Make Changes**: Make your changes and commit them with clear and descriptive commit messages.
    ```sh
    git commit -m "Description of the feature or fix"
    ```

4. **Push Changes**: Push your changes to your forked repository.
    ```sh
    git push origin feature-name
    ```

5. **Submit a Pull Request**: Open a pull request to merge your changes into the main repository.

## License

MuPDF-Viewer is licensed under the MIT License. See the [LICENSE](LICENSE) file for more details.

## References

This project is inspired by and utilizes the MuPDF library. For more information, visit the [MuPDF Android Viewer repository](https://github.com/ArtifexSoftware/mupdf-android-viewer).