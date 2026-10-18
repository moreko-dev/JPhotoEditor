# JPhotoEditor

A lightweight desktop photo editor built with **Java Swing**. JPhotoEditor provides a simple graphical interface for working with images, powered by Java's standard libraries without third-party runtime dependencies.

## Features

- **Desktop GUI** built with Java Swing.
- **Image editing** through the application's built-in editing tools.
- **Open images** directly from the application or pass an image path at launch.
- **Lightweight** with no third-party runtime dependencies.
- **Cross-platform** support for systems that run Java with a graphical desktop environment.

## Tech Stack

- **Java 11+**
- **Java Swing** for the graphical user interface.
- **Apache Maven** for building and managing the project.

## Requirements

Before running JPhotoEditor, make sure you have installed:

- [JDK 11 or newer](https://adoptium.net/)
- [Apache Maven 3.8 or newer](https://maven.apache.org/install.html)

A graphical desktop environment is required because JPhotoEditor is a Swing application.

## Getting Started

### 1. Clone the repository

```bash
git clone https://github.com/moreko-dev/JPhotoEditor.git
cd JPhotoEditor
```

### 2. Build the project

Run the following command from the project root:

```bash
mvn clean package
```

Maven will compile the source code and package the application into an executable JAR.

### 3. Run the application

```bash
java -jar target/photo-editor-1.0.0.jar
```

### Open an image on launch

You can provide an image path as a command-line argument.

**Linux / macOS**

```bash
java -jar target/photo-editor-1.0.0.jar "/path/to/image.png"
```

**Windows (PowerShell)**

```powershell
java -jar target/photo-editor-1.0.0.jar "C:\Users\YourName\Pictures\image.png"
```

Replace the example path with the location of your image.

## Run Without Packaging

To compile the project without creating the JAR:

```bash
mvn compile
```

Then launch the application using the compiled classes:

```bash
java -cp target/classes PhotoEditor
```

## Project Structure

```text
JPhotoEditor/
├── pom.xml
├── README.md
├── .gitignore
└── src/
    └── main/
        └── java/
            └── PhotoEditor.java
```

| File or Directory | Description |
|---|---|
| `pom.xml` | Maven project configuration and build settings. |
| `README.md` | Project documentation and usage instructions. |
| `.gitignore` | Files and directories excluded from Git. |
| `src/main/java/PhotoEditor.java` | Main Java source code for the application. |
| `target/` | Generated build output, created by Maven. |

## Built With AI

This project was developed with assistance from **Claude AI**, by Anthropic, to support implementation and development.

## License

No license has been specified yet. Unless a license is added to the repository, the project should not be assumed to be available for unrestricted reuse or redistribution.