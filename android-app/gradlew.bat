@echo off
setlocal
set "APP_HOME=%~dp0"
set "CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar"
if not exist "%CLASSPATH%" (
  >&2 echo Missing gradle-wrapper.jar. Run ..\scripts\bootstrap-gradle-wrapper.ps1 first.
  exit /b 2
)

if not defined JAVA_HOME goto use_path_java
set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not exist "%JAVA_EXE%" (
  >&2 echo JAVA_HOME does not point to a valid JDK: %JAVA_HOME%
  exit /b 3
)
goto run_gradle

:use_path_java
set "JAVA_EXE=java.exe"
where java.exe >nul 2>nul
if errorlevel 1 (
  >&2 echo Java not found. Set JAVA_HOME to Android Studio's jbr directory.
  exit /b 3
)

:run_gradle
"%JAVA_EXE%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
set "GRADLE_EXIT_CODE=%ERRORLEVEL%"
endlocal & exit /b %GRADLE_EXIT_CODE%
