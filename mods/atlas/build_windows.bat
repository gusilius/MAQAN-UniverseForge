@echo off
setlocal
where java >nul 2>nul || (echo Java not found. Install JDK 17. & pause & exit /b 1)
where gradle >nul 2>nul || (echo Gradle not found. Install Gradle 8.x and add it to PATH. & pause & exit /b 1)
gradle build
if errorlevel 1 (echo BUILD FAILED. Copy the complete error log for diagnosis. & pause & exit /b 1)
echo.
echo Build complete. Check build\libs\
pause
