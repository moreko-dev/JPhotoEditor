# Photo Editor

A desktop photo editor built with Java Swing. It uses only Java's standard library; no third-party runtime dependencies are required.

## Requirements

- JDK 11 or newer
- Apache Maven 3.8 or newer
- A graphical desktop session (the app is a Swing GUI application)

## Build

Open a terminal in this folder and run:

```bash
mvn clean package
```

The executable JAR will be created at:

```text
target/photo-editor-1.0.0.jar
```

## Run

Launch the app:

```bash
java -jar target/photo-editor-1.0.0.jar
```

Open an image directly on launch by providing its path:

```bash
java -jar target/photo-editor-1.0.0.jar "/path/to/image.png"
```

On Windows, use a path such as:

```powershell
java -jar target/photo-editor-1.0.0.jar "C:\Users\YourName\Pictures\image.png"
```

## Run without packaging

Maven can also compile and launch the app with the Java command after compilation:

```bash
mvn compile
java -cp target/classes PhotoEditor
```

## Project layout

```text
PhotoEditor-Maven/
├── pom.xml
├── README.md
└── src/
    └── main/
        └── java/
            └── PhotoEditor.java
```
