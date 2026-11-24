@echo off
setlocal
set ROOT=%~dp0
set VERSION=8.9
set BASE=%USERPROFILE%\.gradle\yapit-dists\gradle-%VERSION%
set GRADLE=%BASE%\gradle-%VERSION%\bin\gradle.bat
if not exist "%GRADLE%" (
  if not exist "%BASE%" mkdir "%BASE%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$u='https://services.gradle.org/distributions/gradle-8.9-bin.zip';$z='%BASE%\gradle.zip';Invoke-WebRequest -Uri $u -OutFile $z;Expand-Archive -Path $z -DestinationPath '%BASE%' -Force"
)
call "%GRADLE%" -p "%ROOT%" %*
